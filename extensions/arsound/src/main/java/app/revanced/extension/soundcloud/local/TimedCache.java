package app.revanced.extension.soundcloud.local;

import java.util.function.Supplier;

/**
 * A value that is expensive to ask for (a call to the system's account manager is a binder call), kept for a short time
 * so a screen that asks on every redraw does not make the call every time. A missing value (null) is never kept: the
 * next ask looks again.
 */
final class TimedCache<T> {
    private final long keepMs;
    private T value;
    private long at;

    TimedCache(long keepMs) {
        this.keepMs = keepMs;
    }

    /** The kept value if it is younger than the keep time, else the loader's answer, which is kept in turn if not null. */
    synchronized T get(long now, Supplier<T> loader) {
        if (value != null && now - at >= 0 && now - at < keepMs) return value;
        T fresh = loader.get();
        value = fresh;
        at = now;
        return fresh;
    }

    /** The kept value is forgotten: the next ask goes to the loader. */
    synchronized void invalidate() {
        value = null;
    }
}
