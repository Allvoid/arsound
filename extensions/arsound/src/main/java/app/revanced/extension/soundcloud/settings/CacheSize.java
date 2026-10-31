package app.revanced.extension.soundcloud.settings;

import android.os.SystemClock;

import app.revanced.extension.soundcloud.player.StreamCache;

/**
 * The space the caches take, counted at most once a minute. The main screen, the storage bar and the cache row each
 * walked the whole cache folder, on every opening of the settings.
 */
final class CacheSize {
    private static final long FRESH_MS = 60_000;
    private static long bytes = -1;
    private static long countedAt;

    private CacheSize() {
    }

    /** Blocks while counting. Callers at the same time wait for the one count instead of walking the folder too. */
    static synchronized long get() {
        long now = SystemClock.elapsedRealtime();
        if (bytes < 0 || now - countedAt > FRESH_MS) {
            bytes = StreamCache.usedBytes() + StreamCache.otherCachesBytes();
            countedAt = now;
        }
        return bytes;
    }

    /** The caches were emptied: the next ask counts again. */
    static synchronized void forget() {
        bytes = -1;
    }
}
