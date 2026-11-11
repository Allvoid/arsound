package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class ResumableDownloadTest {
    private static final String CRLF = "\r\n";
    private static final ResumableDownload.Cancel NEVER = () -> false;
    private static final ResumableDownload.Progress IGNORE = (done, total) -> {
    };

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private ServerSocket server;
    private byte[] data;
    private String sha;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile boolean honourRange = true;
    private volatile int failuresLeft;
    private volatile String lastRange;

    @Before
    public void start() throws Exception {
        data = new byte[300_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31 + i / 7);
        sha = hex(MessageDigest.getInstance("SHA-256").digest(data));
        server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    answer(socket);
                } catch (IOException ignored) {
                    // The test closed the server, or the client hung up (a cancelled download).
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @After
    public void stop() throws IOException {
        server.close();
    }

    /** A bare-bones HTTP/1.1 server: one request per connection, GET only, with Range, one redirect and one error. */
    private void answer(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        String request = in.readLine();
        String range = null;
        for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
            if (line.toLowerCase(Locale.ROOT).startsWith("range:")) range = line.substring(6).trim();
        }
        OutputStream out = socket.getOutputStream();
        if (request.startsWith("GET /redirect")) {
            send(out, "HTTP/1.1 302 Found" + CRLF + "Location: /file" + CRLF + "Content-Length: 0" + CRLF
                    + "Connection: close" + CRLF + CRLF);
            return;
        }
        if (request.startsWith("GET /missing")) {
            send(out, "HTTP/1.1 404 Not Found" + CRLF + "Content-Length: 0" + CRLF + "Connection: close" + CRLF + CRLF);
            return;
        }
        requests.incrementAndGet();
        lastRange = range;
        if (failuresLeft > 0) {
            failuresLeft--;
            send(out, "HTTP/1.1 503 Service Unavailable" + CRLF + "Content-Length: 0" + CRLF + "Connection: close" + CRLF + CRLF);
            return;
        }
        int from = 0;
        int code = 200;
        if (range != null && honourRange) {
            from = Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
            code = 206;
        }
        send(out, "HTTP/1.1 " + code + (code == 206 ? " Partial Content" : " OK") + CRLF + "Content-Length: "
                + (data.length - from) + CRLF + "Connection: close" + CRLF + CRLF);
        out.write(data, from, data.length - from);
        out.flush();
    }

    private static void send(OutputStream out, String head) throws IOException {
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getLocalPort() + path;
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format("%02x", b & 0xFF));
        return text.toString();
    }

    @Test
    public void aFileIsDownloadedAndChecked() throws IOException {
        File target = new File(temporary.getRoot(), "model/file.bin");
        ResumableDownload.fetch(url("/file"), target, data.length, sha, NEVER, IGNORE);
        assertArrayEquals(data, Files.readAllBytes(target.toPath()));
        assertFalse(new File(target.getPath() + ".part").exists());
    }

    @Test
    public void aRedirectIsFollowed() throws IOException {
        File target = new File(temporary.getRoot(), "r.bin");
        ResumableDownload.fetch(url("/redirect"), target, data.length, sha, NEVER, IGNORE);
        assertArrayEquals(data, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void anEarlierPartGoesOnFromWhereItStopped() throws IOException {
        File target = new File(temporary.getRoot(), "p.bin");
        Files.write(new File(target.getPath() + ".part").toPath(), Arrays.copyOf(data, 100_000));
        ResumableDownload.fetch(url("/file"), target, data.length, sha, NEVER, IGNORE);
        assertEquals("bytes=100000-", lastRange);
        assertArrayEquals(data, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void aServerThatIgnoresTheRangeStartsTheFileAgain() throws IOException {
        honourRange = false;
        File target = new File(temporary.getRoot(), "i.bin");
        Files.write(new File(target.getPath() + ".part").toPath(), Arrays.copyOf(data, 100_000));
        ResumableDownload.fetch(url("/file"), target, data.length, sha, NEVER, IGNORE);
        assertArrayEquals(data, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void aBusyServerIsTriedAgain() throws IOException {
        failuresLeft = 1;
        File target = new File(temporary.getRoot(), "b.bin");
        ResumableDownload.fetch(url("/file"), target, data.length, sha, NEVER, IGNORE);
        assertArrayEquals(data, Files.readAllBytes(target.toPath()));
        assertEquals(2, requests.get());
    }

    @Test
    public void aMissingFileIsNotTriedAgain() throws IOException {
        long start = System.currentTimeMillis();
        try {
            ResumableDownload.fetch(url("/missing"), new File(temporary.getRoot(), "m.bin"), 10, sha, NEVER, IGNORE);
            fail("expected an error");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("404"));
        }
        assertTrue("it did not wait for retries", System.currentTimeMillis() - start < 1000);
    }

    @Test
    public void aFileThatIsAlreadyThereIsNotDownloadedAgain() throws IOException {
        File target = new File(temporary.getRoot(), "t.bin");
        Files.write(target.toPath(), data);
        ResumableDownload.fetch(url("/file"), target, data.length, sha, NEVER, IGNORE);
        assertEquals(0, requests.get());
    }

    @Test
    public void aWrongChecksumRemovesTheFileAndSaysSo() throws IOException {
        File target = new File(temporary.getRoot(), "w.bin");
        try {
            ResumableDownload.fetch(url("/file"), target, data.length, "0".repeat(64), NEVER, IGNORE);
            fail("expected a bad checksum");
        } catch (ResumableDownload.BadChecksum expected) {
            // The file is not under its final name, and the part is gone so the next try starts clean.
        }
        assertFalse(target.exists());
        assertFalse(new File(target.getPath() + ".part").exists());
    }

    @Test
    public void aCancelKeepsWhatWasLoaded() throws IOException {
        File target = new File(temporary.getRoot(), "c.bin");
        final int[] calls = {0};
        try {
            // Asked before the try, before the connection is opened and before the first piece is written: the
            // cancel comes with the second piece.
            ResumableDownload.fetch(url("/file"), target, data.length, sha, () -> ++calls[0] > 3, IGNORE);
            fail("expected a cancel");
        } catch (ResumableDownload.Cancelled expected) {
            // The part stays for the next try.
        }
        assertFalse(target.exists());
        assertTrue(new File(target.getPath() + ".part").length() > 0);
    }

    @Test
    public void aHttpAddressIsRefusedExceptForTheMachineItself() {
        try {
            ResumableDownload.fetch("http://example.com/file", new File(temporary.getRoot(), "h.bin"), 10, sha, NEVER, IGNORE);
            fail("expected a refusal");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("HTTPS"));
        }
    }

    @Test
    public void theProgressReachesTheEnd() throws IOException {
        File target = new File(temporary.getRoot(), "g.bin");
        final long[] last = {0, 0};
        ResumableDownload.fetch(url("/file"), target, data.length, sha, NEVER, (done, total) -> {
            last[0] = done;
            last[1] = total;
        });
        assertEquals(data.length, last[0]);
        assertEquals(data.length, last[1]);
    }
}
