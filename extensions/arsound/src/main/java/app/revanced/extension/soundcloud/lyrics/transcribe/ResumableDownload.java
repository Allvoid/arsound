package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Downloads one file over HTTPS, continuing where an earlier try stopped, and checks its SHA-256 before putting it in
 * place. The half-loaded file lives next to the target as {@code <name>.part} and stays when the download is
 * cancelled or fails, so the next try goes on from there.
 * <p>
 * Called by {@link AsrInstaller} for the speech model and the native library. Redirects are followed by hand and only
 * to HTTPS addresses, because the files are executable code and big models: nothing may come from a plain-text link.
 */
final class ResumableDownload {
    /** Told how far the download is. Called on the thread that downloads. */
    interface Progress {
        void onBytes(long done, long total);
    }

    /** Asked between blocks of data; true stops the download with {@link Cancelled}. */
    interface Cancel {
        boolean isCancelled();
    }

    /** The download was stopped on request; the partial file is kept. */
    static final class Cancelled extends IOException {
        Cancelled() {
            super("cancelled");
        }
    }

    /** The finished file is not the one the code expects. The partial file is removed: loading it again would not help. */
    static final class BadChecksum extends IOException {
        BadChecksum(String name) {
            super("The file " + name + " does not match its checksum");
        }
    }

    /** An answer that trying again will not change: a missing file, a refused address. */
    private static final class Final extends IOException {
        Final(String message) {
            super(message);
        }
    }

    private static final int MAX_REDIRECTS = 5;
    private static final int ATTEMPTS = 4;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final String USER_AGENT = "Arsound";

    /** The connection reading now, so a cancel can close it: a read waits for the network and asks nothing meanwhile. */
    private static volatile HttpURLConnection active;

    private ResumableDownload() {
    }

    /** Closes the connection that reads now, if any: the blocked read ends at once, and the cancel is seen. */
    static void abortActive() {
        HttpURLConnection connection = active;
        if (connection == null) return;
        // Off the caller's thread: closing a socket can block for a moment.
        new Thread(connection::disconnect, "Arsound download abort").start();
    }

    /**
     * Makes sure {@code target} exists with the expected size and checksum. A file that is already there with the
     * expected size is kept as it is: only a finished download is ever renamed to its final name.
     *
     * @param expectedSize The size the finished file has; also what the progress counts toward.
     * @param sha256       Lower-case hex.
     */
    static void fetch(String url, File target, long expectedSize, String sha256, Cancel cancel, Progress progress)
            throws IOException {
        if (target.isFile() && target.length() == expectedSize) return;
        File partial = new File(target.getPath() + ".part");
        File folder = target.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs()) throw new IOException("No folder for " + target);

        for (int attempt = 0; ; attempt++) {
            if (cancel.isCancelled()) throw new Cancelled();
            try {
                download(url, partial, expectedSize, cancel, progress);
                break;
            } catch (Cancelled | Final ex) {
                throw ex;
            } catch (IOException ex) {
                // A full storage stays full: no tries again, and the user is told to free some space.
                if (DiskErrors.isNoSpace(ex)) throw new AsrInstaller.NoSpace(Math.max(0, expectedSize - partial.length()));
                if (attempt == ATTEMPTS - 1) throw ex;
                pause(1500L << attempt, cancel);
            }
        }
        if (partial.length() != expectedSize) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw new IOException("The size of " + target.getName() + " is " + partial.length() + ", not " + expectedSize);
        }
        if (!sha256.equals(hashOf(partial))) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw new BadChecksum(target.getName());
        }
        if (!partial.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!partial.renameTo(target)) throw new IOException("Could not put " + target.getName() + " in place");
        }
    }

    private static void download(String url, File partial, long expectedSize, Cancel cancel, Progress progress)
            throws IOException {
        long have = partial.isFile() ? partial.length() : 0;
        if (have > expectedSize) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            have = 0;
        }
        if (have == expectedSize) {
            progress.onBytes(have, expectedSize);
            return;
        }
        HttpURLConnection connection = open(url, have, cancel);
        try {
            int code = connection.getResponseCode();
            if (code == 416) {
                // The server does not like the position: the partial file is no good.
                //noinspection ResultOfMethodCallIgnored
                partial.delete();
                throw new IOException("HTTP 416");
            }
            if (code != 200 && code != 206) {
                // A server that is busy or failing may do better in a moment; a missing file will not.
                boolean again = code == 408 || code == 429 || code >= 500;
                throw again ? new IOException("HTTP " + code) : new Final("HTTP " + code);
            }
            // A server that ignores the range sends the whole file with 200: start the file again.
            if (code == 200) have = 0;
            try (InputStream input = connection.getInputStream();
                 RandomAccessFile output = new RandomAccessFile(partial, "rw")) {
                output.setLength(have);
                output.seek(have);
                byte[] buffer = new byte[64 * 1024];
                long done = have;
                long lastReport = 0;
                int count;
                while ((count = input.read(buffer)) > 0) {
                    if (cancel.isCancelled()) throw new Cancelled();
                    output.write(buffer, 0, count);
                    done += count;
                    long now = System.nanoTime();
                    if (now - lastReport > 150_000_000L) {
                        lastReport = now;
                        progress.onBytes(done, expectedSize);
                    }
                }
                progress.onBytes(done, expectedSize);
            } catch (IOException ex) {
                // A read cut by a cancel is the cancel, not a failure to try again.
                if (cancel.isCancelled()) throw new Cancelled();
                throw ex;
            }
        } finally {
            if (active == connection) active = null;
            connection.disconnect();
        }
    }

    /**
     * Opens the address, following redirects to HTTPS addresses, asking for the bytes from {@code from} on. The
     * connection is {@link #active} from before it connects: a cancel then ends the wait for a server that does not
     * answer, which took up to the connect and read time limits for each redirect.
     */
    private static HttpURLConnection open(String address, long from, Cancel cancel) throws IOException {
        String current = address;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL url = new URL(current);
            if (!"https".equals(url.getProtocol()) && !isLoopback(url)) throw new Final("Not an HTTPS address: " + current);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (from > 0) connection.setRequestProperty("Range", "bytes=" + from + "-");
            // First made the one to close, then the cancel is looked at: a cancel between the two is not missed.
            active = connection;
            int code;
            try {
                if (cancel.isCancelled()) throw new Cancelled();
                code = connection.getResponseCode();
            } catch (IOException ex) {
                if (active == connection) active = null;
                connection.disconnect();
                // A wait cut by a cancel is the cancel, not a failure to try again.
                if (cancel.isCancelled()) throw new Cancelled();
                throw ex;
            }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new Final("Redirect without a place");
                current = new URL(url, location).toString();
                continue;
            }
            return connection;
        }
        throw new Final("Too many redirects");
    }

    /** Only the tests talk to the machine itself over plain HTTP. */
    private static boolean isLoopback(URL url) {
        return "http".equals(url.getProtocol()) && ("127.0.0.1".equals(url.getHost()) || "localhost".equals(url.getHost()));
    }

    private static void pause(long millis, Cancel cancel) throws Cancelled {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (cancel.isCancelled()) throw new Cancelled();
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new Cancelled();
            }
        }
    }

    /** The SHA-256 of a file as lower-case hex. */
    static String hashOf(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = input.read(buffer)) > 0) digest.update(buffer, 0, count);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b & 0xFF));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException(ex);
        }
    }
}
