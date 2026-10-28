package app.revanced.extension.soundcloud.shared;

/**
 * Carries a value from the start of a SoundCloud call to its result on the same thread: one hook sees the
 * arguments, the other the result, and nothing links the two but the thread. A value is taken once.
 */
public final class CallSlot<T> {
    private final ThreadLocal<T> value = new ThreadLocal<>();

    /** Keeps the value for the result of the call that has just begun on this thread. */
    public void put(T newValue) {
        value.set(newValue);
    }

    /** The value kept on this thread, which is then forgotten; null if there is none. */
    public T take() {
        T taken = value.get();
        value.remove();
        return taken;
    }
}
