package timber.log;

/** The Timber logger inside SoundCloud. Only the parts used by the extension, with the names left by R8. */
public final class Timber {
    public abstract static class Tree {
        /** Whether a line of this priority is wanted. */
        public boolean h(int priority) {
            return true;
        }

        /** Receives one formatted line. */
        public abstract void i(String tag, int priority, String message, Throwable throwable);
    }
}
