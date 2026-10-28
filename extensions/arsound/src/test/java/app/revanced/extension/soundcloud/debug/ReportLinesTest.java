package app.revanced.extension.soundcloud.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReportLinesTest {
    @Test
    public void linesAreNameAndValue() {
        assertEquals("a: 1\nb: x\n", new ReportLines().add("a", 1).add("b", "x").toString());
    }

    @Test
    public void probeWritesTheValue() {
        assertEquals("a: 5\n", new ReportLines().probe("a", () -> 5).toString());
    }

    @Test
    public void failedProbeWritesTheReasonAndTheNextOneStillRuns() {
        String text = new ReportLines()
                .probe("a", () -> {
                    throw new SecurityException("denied");
                })
                .probe("b", () -> {
                    throw new NoSuchMethodError("newer API");
                })
                .probe("c", () -> "fine")
                .toString();
        assertTrue(text.contains("a: unavailable (java.lang.SecurityException: denied)"));
        assertTrue(text.contains("b: unavailable (java.lang.NoSuchMethodError: newer API)"));
        assertTrue(text.endsWith("c: fine\n"));
    }
}
