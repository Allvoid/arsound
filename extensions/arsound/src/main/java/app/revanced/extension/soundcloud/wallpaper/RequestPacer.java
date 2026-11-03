package app.revanced.extension.soundcloud.wallpaper;

import java.io.IOException;

/**
 * Spaces the calls to one service out: {@link #pause} returns once at least the gap has passed since the call before it,
 * from whatever thread. Wallhaven allows 45 calls a minute and Commons asks for a polite pace; the two keep one pacer
 * each instead of one copy of this wait each.
 */
final class RequestPacer {
    private final long gapMs;
    private final Object lock = new Object();
    private long lastCall;

    RequestPacer(long gapMs) {
        this.gapMs = gapMs;
    }

    /** Waits for the gap since the last call and counts this one. Blocks: call it off the main thread. */
    void pause() throws IOException {
        synchronized (lock) {
            long wait = lastCall + gapMs - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted");
                }
            }
            lastCall = System.currentTimeMillis();
        }
    }
}
