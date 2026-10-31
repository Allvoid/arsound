package app.revanced.extension.soundcloud.shared;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;

/**
 * Work done one task after another on one thread of its own, which ends when it has been idle a while. For work that
 * comes in bursts (hundreds of finished downloads after a return to the app): the shared pool of Utils starts a new
 * thread for every task beyond three that are busy.
 */
public final class SerialWork implements Executor {
    private static final long IDLE_SECONDS = 30;

    private final ThreadPoolExecutor executor;

    /** @param name The name of the thread, as it shows in a list of the threads. */
    public SerialWork(String name) {
        executor = new ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, name);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        });
        executor.allowCoreThreadTimeOut(true);
    }

    /** Runs the task after the ones before it; a task that throws is logged and does not stop the next ones. */
    @Override
    public void execute(Runnable task) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (Throwable ex) {
                Logger.printException(() -> "Serial task failed", ex);
            }
        });
    }
}
