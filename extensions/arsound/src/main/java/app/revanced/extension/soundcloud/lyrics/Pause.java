package app.revanced.extension.soundcloud.lyrics;

import java.io.IOException;

/** A wait that a lookup which is cancelled meanwhile does not have to sit out. */
final class Pause {
    private static final long STEP_MS = 50;

    private Pause() {
    }

    /**
     * Waits, in short steps so that a cancel is noticed within one step.
     *
     * @param cancel Null when nobody can cancel.
     * @throws IOException "Lookup cancelled" when cancelled, "Interrupted" (with the thread's flag set again) when the
     *                     thread is interrupted.
     */
    static void cancellable(long ms, LookupCancel cancel) throws IOException {
        long end = System.currentTimeMillis() + ms;
        try {
            for (long left = ms; left > 0; left = end - System.currentTimeMillis()) {
                if (cancel != null && cancel.isCancelled()) throw new IOException("Lookup cancelled");
                Thread.sleep(Math.min(left, STEP_MS));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", ex);
        }
    }
}
