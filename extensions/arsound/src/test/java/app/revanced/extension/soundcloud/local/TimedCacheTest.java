package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class TimedCacheTest {
    @Test
    public void theLoaderIsAskedOncePerKeepTime() {
        TimedCache<String> cache = new TimedCache<>(1_000);
        AtomicInteger calls = new AtomicInteger();
        assertEquals("a", cache.get(0, () -> {
            calls.incrementAndGet();
            return "a";
        }));
        assertEquals("a", cache.get(999, () -> {
            calls.incrementAndGet();
            return "b";
        }));
        assertEquals(1, calls.get());
        assertEquals("b", cache.get(1_000, () -> {
            calls.incrementAndGet();
            return "b";
        }));
        assertEquals(2, calls.get());
    }

    @Test
    public void aMissingValueIsAskedAgainAtOnce() {
        TimedCache<String> cache = new TimedCache<>(1_000);
        assertNull(cache.get(0, () -> null));
        assertEquals("a", cache.get(1, () -> "a"));
    }

    @Test
    public void anInvalidatedValueIsAskedAgain() {
        TimedCache<String> cache = new TimedCache<>(1_000);
        cache.get(0, () -> "a");
        cache.invalidate();
        assertEquals("b", cache.get(1, () -> "b"));
    }
}
