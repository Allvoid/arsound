package app.revanced.extension.soundcloud.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import app.revanced.extension.shared.Logger;

/**
 * Asks the network for {@link NetDiag}: for each site the DNS of the phone, the custom DNS and an HTTPS HEAD. A few
 * sites are asked at the same time and every step has a time limit, so on a dead network the whole check takes about
 * two rounds of waiting, not the sum over all sites. Blocks the thread it runs on: start it off the main thread.
 * <p>
 * The HTTPS request goes to the address that the app itself would use: the one the custom DNS gave, else the phone's.
 * So a provider that poisons the DNS of the phone shows up as a site that does not answer there but does through the
 * custom DNS.
 */
public final class NetDiagRunner {
    private static final int PARALLEL = 5;
    private static final int HTTP_TIMEOUT_MS = 10_000;
    private static final long SYSTEM_DNS_TIMEOUT_MS = 5_000;
    private static final String TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace";
    /** The longest first line of an answer that is read; a server that sends no newline must not fill the memory. */
    private static final int MAX_LINE = 512;
    /**
     * The system lookups run here so that one that hangs can be given up on. A lookup cannot be interrupted, so a hung
     * one keeps its thread until the resolver gives up; the pool is bounded so that repeated checks cannot pile up
     * threads (the lookups queued behind them time out as usual).
     */
    private static final ExecutorService LOOKUPS = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "arsound-netdiag-lookup");
        thread.setDaemon(true);
        return thread;
    });

    /** Called from the checking threads, one call at a time; show the result on the main thread. */
    public interface Listener {
        void onHost(NetDiag.Row row, int done, int total);
    }

    /** The outcome of a whole check. */
    public static final class Result {
        public final List<NetDiag.Row> rows;
        /** "" when the trace did not answer. */
        public final String country;
        public final boolean cancelled;

        Result(List<NetDiag.Row> rows, String country, boolean cancelled) {
            this.rows = rows;
            this.country = country;
            this.cancelled = cancelled;
        }
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();

    /** Stops the sites that have not started; the ones under way finish. */
    public void cancel() {
        cancelled.set(true);
    }

    public Result run(List<String> hosts, boolean withCountry, Listener listener) {
        int total = hosts.size();
        List<NetDiag.Row> rows = new ArrayList<>(Collections.<NetDiag.Row>nCopies(total, null));
        String[] country = {""};
        Object progress = new Object();
        int[] done = {0};
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(PARALLEL, total + 1)), runnable -> {
            Thread thread = new Thread(runnable, "arsound-netdiag");
            thread.setDaemon(true);
            return thread;
        });
        List<Future<?>> tasks = new ArrayList<>();
        try {
            if (withCountry) tasks.add(pool.submit(() -> {
                country[0] = readCountry();
            }));
            for (int i = 0; i < total; i++) {
                final int index = i;
                tasks.add(pool.submit(() -> {
                    NetDiag.Row row = new NetDiag.Row(hosts.get(index));
                    if (cancelled.get()) {
                        row.skipped = true;
                    } else {
                        try {
                            probe(row);
                        } catch (RuntimeException ex) {
                            row.httpError = "diagnostics failed: " + ex;
                        }
                    }
                    synchronized (progress) {
                        rows.set(index, row);
                        done[0]++;
                        if (listener != null) listener.onHost(row, done[0], total);
                    }
                }));
            }
            for (Future<?> task : tasks) {
                try {
                    task.get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    cancelled.set(true);
                    break;
                } catch (Exception ex) {
                    Logger.printInfo(() -> "Network diagnostics: a probe failed: " + ex);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        for (int i = 0; i < total; i++) {
            if (rows.get(i) == null) {
                NetDiag.Row row = new NetDiag.Row(hosts.get(i));
                row.skipped = true;
                rows.set(i, row);
            }
        }
        return new Result(rows, country[0], cancelled.get());
    }

    /** One site: the DNS of the phone, the custom DNS, then HTTPS. */
    private static void probe(NetDiag.Row row) {
        String host = row.host;
        List<InetAddress> system = new ArrayList<>();
        Future<InetAddress[]> lookup = LOOKUPS.submit(() -> InetAddress.getAllByName(host));
        try {
            InetAddress[] found = lookup.get(SYSTEM_DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            for (InetAddress address : found) {
                system.add(address);
                row.systemAddresses.add(address.getHostAddress());
            }
            if (system.isEmpty()) row.systemError = "no address";
        } catch (TimeoutException ex) {
            row.systemError = "timed out";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            row.systemError = "interrupted";
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            row.systemError = cause instanceof java.net.UnknownHostException ? "unknown host" : String.valueOf(cause);
        }

        CustomDns.Probe custom = CustomDns.probe(host);
        row.customActive = custom.active;
        row.customAddresses = custom.addresses;
        row.customMethod = custom.method;
        row.customError = custom.error;
        row.customMs = custom.elapsedMs;

        // The address the app would connect to: the custom DNS first, as in the lookup of the injected DNS.
        InetAddress target = null;
        List<String> literals = custom.addresses.isEmpty() ? row.systemAddresses : custom.addresses;
        for (String literal : literals) {
            try {
                InetAddress address = InetAddress.getByName(literal);
                if (target == null || (address.getAddress().length == 4 && target.getAddress().length != 4)) target = address;
            } catch (java.net.UnknownHostException ignored) {
                // A literal is never looked up, so this does not happen.
            }
        }
        long started = System.nanoTime();
        if (target == null) {
            row.httpError = "no address to connect to";
        } else {
            try {
                row.httpStatus = head(host, target);
                if (row.httpStatus <= 0) row.httpError = "no HTTP answer";
            } catch (IOException ex) {
                row.httpError = shortError(ex);
            }
        }
        row.httpMs = (System.nanoTime() - started) / 1_000_000;
    }

    private static String shortError(Exception ex) {
        String message = ex.getMessage();
        String name = ex.getClass().getSimpleName();
        if (message == null || message.isEmpty()) return name;
        return name + ": " + (message.length() > 80 ? message.substring(0, 80) : message);
    }

    /**
     * HTTPS HEAD to the address, with the name of the site in the handshake and the certificate checked against it.
     *
     * @return The status code, or 0 if the answer was not HTTP.
     */
    private static int head(String host, InetAddress address) throws IOException {
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(address, 443), HTTP_TIMEOUT_MS);
            plain.setSoTimeout(HTTP_TIMEOUT_MS);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket tls = (SSLSocket) factory.createSocket(plain, host, 443, true)) {
                tls.startHandshake();
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.getSession())) {
                    throw new SSLPeerUnverifiedException("certificate is not for " + host);
                }
                OutputStream out = tls.getOutputStream();
                out.write(("HEAD / HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: Arsound-diagnostics\r\n"
                        + "Accept: */*\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return NetDiag.parseStatusLine(boundedLine(tls.getInputStream()));
            }
        }
    }

    /**
     * One line of an answer, read byte by byte. Null if the line is longer than {@link #MAX_LINE} or the stream ends
     * with no data, so a server that sends no newline cannot make the memory grow.
     */
    private static String boundedLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return line.toString();
            if (b == '\r') continue;
            if (line.length() >= MAX_LINE) return null;
            line.append((char) (b & 0xFF));
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static String readCountry() {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(TRACE_URL).openConnection();
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            try {
                InputStream in = connection.getInputStream();
                StringBuilder text = new StringBuilder();
                String line;
                while (text.length() < 4_096 && (line = boundedLine(in)) != null) text.append(line).append('\n');
                return NetDiag.parseTraceCountry(text.toString());
            } finally {
                connection.disconnect();
            }
        } catch (IOException ex) {
            return "";
        }
    }
}
