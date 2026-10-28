package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class DebouncedTest {
    @Test
    public void manyRequestsRunTheActionOnce() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        Debounced debounced = new Debounced(100, () -> {
            runs.incrementAndGet();
            done.countDown();
        });

        for (int i = 0; i < 10; i++) {
            debounced.request();
            Thread.sleep(10);
        }

        assertTrue(done.await(2, TimeUnit.SECONDS));
        // Give a second run time to show up if there was one.
        Thread.sleep(300);
        assertEquals(1, runs.get());
    }

    @Test
    public void waitsForTheLastRequest() throws Exception {
        long[] ranAt = new long[1];
        CountDownLatch done = new CountDownLatch(1);
        Debounced debounced = new Debounced(150, () -> {
            ranAt[0] = System.nanoTime();
            done.countDown();
        });

        debounced.request();
        Thread.sleep(80);
        long lastRequest = System.nanoTime();
        debounced.request();

        assertTrue(done.await(2, TimeUnit.SECONDS));
        // Allow for timer rounding.
        assertTrue(TimeUnit.NANOSECONDS.toMillis(ranAt[0] - lastRequest) >= 100);
    }

    @Test
    public void runsAgainForALaterRequest() throws Exception {
        CountDownLatch twice = new CountDownLatch(2);
        Debounced debounced = new Debounced(30, twice::countDown);

        debounced.request();
        Thread.sleep(200);
        debounced.request();

        assertTrue(twice.await(2, TimeUnit.SECONDS));
    }
}
