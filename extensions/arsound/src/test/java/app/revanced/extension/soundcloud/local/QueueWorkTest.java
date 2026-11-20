package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

public class QueueWorkTest {
    /** Runs what it is given only when told, like another thread. */
    private static final class Later implements Executor {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runAll() {
            List<Runnable> now = new ArrayList<>(tasks);
            tasks.clear();
            for (Runnable task : now) task.run();
        }
    }

    @Test
    public void theFirstLookOnTheMainThreadLoadsInTheBackgroundAndTellsWhenDone() {
        Later background = new Later();
        AtomicInteger told = new AtomicInteger();
        AtomicInteger loads = new AtomicInteger();
        String[] there = {null};
        QueueWork.OffMainLoad<String> load = new QueueWork.OffMainLoad<>(background, told::incrementAndGet);
        assertNull(load.read(true, () -> there[0], () -> {
            loads.incrementAndGet();
            return there[0] = "queue";
        }));
        // A second look before the load ended does not start another one.
        assertNull(load.read(true, () -> there[0], () -> "x"));
        assertEquals(1, background.tasks.size());
        background.runAll();
        assertEquals(1, loads.get());
        assertEquals(1, told.get());
        assertEquals("queue", load.read(true, () -> there[0], () -> "x"));
    }

    @Test
    public void aBackgroundLookLoadsAtOnce() {
        QueueWork.OffMainLoad<String> load = new QueueWork.OffMainLoad<>(new Later(), () -> {
        });
        assertEquals("queue", load.read(false, () -> null, () -> "queue"));
    }

    @Test
    public void aTickWhoseFirstLookIsOnTheMainThreadStillArmsTheTimerAndGivesTheLikes() {
        Later background = new Later();
        AtomicInteger scheduled = new AtomicInteger();
        AtomicInteger given = new AtomicInteger();
        QueueWork.tick(background, () -> true, scheduled::incrementAndGet, given::incrementAndGet);
        // Nothing is looked at on the calling thread.
        assertEquals(0, scheduled.get());
        background.runAll();
        assertEquals(1, scheduled.get());
        assertEquals(1, given.get());
    }

    @Test
    public void aTickWithNoQueueDoesNothing() {
        Later background = new Later();
        AtomicInteger scheduled = new AtomicInteger();
        QueueWork.tick(background, () -> false, scheduled::incrementAndGet, scheduled::incrementAndGet);
        background.runAll();
        assertEquals(0, scheduled.get());
    }

    @Test
    public void aLoadThatFoundNothingDoesNotSendTheScreenBackToLook() {
        Later background = new Later();
        AtomicInteger told = new AtomicInteger();
        QueueWork.OffMainLoad<String> load = new QueueWork.OffMainLoad<>(background, told::incrementAndGet);
        assertNull(load.read(true, () -> null, () -> null));
        background.runAll();
        assertEquals(0, told.get());
    }

    @Test
    public void aScreenThatGoesLeavesTheListenerOfTheNewerScreen() {
        QueueWork.ListenerSlot slot = new QueueWork.ListenerSlot();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        Runnable one = first::incrementAndGet;
        Runnable two = second::incrementAndGet;
        slot.set(one);
        slot.set(two);
        slot.clear(one);
        slot.run();
        assertEquals(0, first.get());
        assertEquals(1, second.get());
        slot.clear(two);
        slot.run();
        assertEquals(1, second.get());
    }
}
