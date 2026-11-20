package app.revanced.extension.soundcloud.login;

/**
 * Counts the wipes of an account's data that SoundCloud has started and not yet ended, whoever started them (a switch of
 * ours, or the user's own sign-out in SoundCloud). Signing in with a kept account while one runs would restart the app and
 * cut it off, so the sign-in waits. A wipe whose end never comes (SoundCloud changed its code) does not block for ever.
 */
final class PurgeGate {
    /** A wipe that has not ended after this long is taken for lost: the same time the mark of a switch lives. */
    static final long LOST_AFTER_MS = AccountVault.PENDING_HERE_FOR_MS;

    private int running;
    private long lastBeganAt;

    synchronized void begin(long now) {
        running++;
        lastBeganAt = now;
    }

    synchronized void end() {
        if (running > 0) running--;
    }

    synchronized boolean busy(long now) {
        return running > 0 && now - lastBeganAt >= 0 && now - lastBeganAt < LOST_AFTER_MS;
    }
}
