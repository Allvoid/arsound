package app.revanced.extension.soundcloud.local;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * How the daily like queue keeps its disk and account work off the main thread, with the threads passed in so the rules
 * can be tried without Android.
 */
final class QueueWork {
    private QueueWork() {
    }

    /** One load of the queue at a time off the main thread, and the screens told when it ended. */
    static final class OffMainLoad<T> {
        private final AtomicBoolean loading = new AtomicBoolean();
        private final Executor background;
        private final Runnable loaded;

        OffMainLoad(Executor background, Runnable loaded) {
            this.background = background;
            this.loaded = loaded;
        }

        /**
         * @param main  Whether the caller is the main thread.
         * @param ready What is there already, or null.
         * @param load  Reads it (the file, the account): blocks.
         * @return The value; null on the main thread when it is not there yet (a load is started, and {@code loaded}
         * runs when it ends, so the caller can look again).
         */
        T read(boolean main, Supplier<T> ready, Supplier<T> load) {
            T value = ready.get();
            if (value != null) return value;
            if (!main) return load.get();
            if (loading.compareAndSet(false, true)) {
                background.execute(() -> {
                    try {
                        load.get();
                    } finally {
                        loading.set(false);
                    }
                    loaded.run();
                });
            }
            return null;
        }
    }

    /**
     * The tick of the daily likes, whichever thread asks: whether a queue waits is looked at in the background (the first
     * look reads the file), so a start whose first look comes on the main thread still arms the timer and gives the likes.
     */
    static void tick(Executor background, BooleanSupplier hasQueue, Runnable schedule, Runnable giveLikes) {
        background.execute(() -> {
            if (!hasQueue.getAsBoolean()) return;
            schedule.run();
            giveLikes.run();
        });
    }
}
