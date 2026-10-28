package app.revanced.extension.soundcloud.debug;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Reads the output of a command, but never waits for it longer than the limit: a command that hangs (logcat on
 * some phones) is stopped and what it printed so far is kept.
 */
public final class ProcessCapture {
    /** The time a stopped command gets to hand over its last bytes. */
    private static final long STOP_GRACE_MS = 500;

    public static final class Result {
        public final byte[] output;
        public final boolean timedOut;
        /** Why reading the output ended early, or null. */
        public final IOException failure;

        Result(byte[] output, boolean timedOut, IOException failure) {
            this.output = output;
            this.timedOut = timedOut;
            this.failure = failure;
        }
    }

    private ProcessCapture() {
    }

    /**
     * @param process Its error output must be merged into the standard one.
     * @param maxBytes The size limit of {@link RedactedCopy}; the output has its secrets hidden.
     */
    public static Result run(Process process, long timeoutMs, long maxBytes) throws InterruptedException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        IOException[] failure = new IOException[1];
        Thread reader = new Thread(() -> {
            try {
                RedactedCopy.copy(process.getInputStream(), collected, maxBytes);
            } catch (IOException ex) {
                failure[0] = ex;
            }
        }, "arsound-process-capture");
        reader.setDaemon(true);
        reader.start();

        reader.join(timeoutMs);
        boolean timedOut = reader.isAlive();
        if (timedOut) {
            process.destroy();
            reader.join(STOP_GRACE_MS);
        }
        // A reader that is stopped by closing the pipe ends with an exception that is not the command's fault.
        return new Result(collected.toByteArray(), timedOut, timedOut ? null : failure[0]);
    }
}
