package app.revanced.extension.soundcloud.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

public class ProcessCaptureTest {
    /** A command that prints some text and then either ends or hangs until it is destroyed. */
    private static final class FakeProcess extends Process {
        final CountDownLatch destroyed = new CountDownLatch(1);
        final String text;
        final boolean hangs;

        FakeProcess(String text, boolean hangs) {
            this.text = text;
            this.hangs = hangs;
        }

        @Override
        public InputStream getInputStream() {
            final ByteArrayInputStream printed = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
                }

                /** Like a pipe: hands over what is there, and waits only when there is nothing. */
                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (printed.available() > 0 || !hangs) return printed.read(buffer, offset, length);
                    try {
                        destroyed.await();
                    } catch (InterruptedException ex) {
                        throw new IOException(ex);
                    }
                    return -1;
                }
            };
        }

        @Override
        public OutputStream getOutputStream() {
            return null;
        }

        @Override
        public InputStream getErrorStream() {
            return null;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyed.countDown();
        }
    }

    @Test
    public void finishedCommandGivesItsOutput() throws Exception {
        ProcessCapture.Result result = ProcessCapture.run(new FakeProcess("line 1\nline 2\n", false), 2_000, 10_000);
        assertEquals("line 1\nline 2\n", new String(result.output, StandardCharsets.UTF_8));
        assertFalse(result.timedOut);
        assertNull(result.failure);
    }

    @Test
    public void hangingCommandIsStoppedAndKeepsWhatItPrinted() throws Exception {
        FakeProcess hanging = new FakeProcess("before the hang\n", true);
        long start = System.currentTimeMillis();
        ProcessCapture.Result result = ProcessCapture.run(hanging, 200, 10_000);
        assertTrue(result.timedOut);
        assertEquals("before the hang\n", new String(result.output, StandardCharsets.UTF_8));
        assertEquals(0, hanging.destroyed.getCount());
        assertTrue("must not wait for the command", System.currentTimeMillis() - start < 3_000);
    }

    @Test
    public void outputHasItsSecretsHiddenAndItsSizeLimited() throws Exception {
        ProcessCapture.Result result = ProcessCapture.run(
                new FakeProcess("sc://auth?code=1\nsecond line\n", false), 2_000, 25);
        String text = new String(result.output, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("sc://auth?<redacted>\n"));
        assertTrue(text.contains("[cut"));
        assertFalse(text.contains("second line"));
    }
}
