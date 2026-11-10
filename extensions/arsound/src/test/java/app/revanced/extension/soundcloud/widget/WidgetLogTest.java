package app.revanced.extension.soundcloud.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

public class WidgetLogTest {
    private static File directory() throws Exception {
        return Files.createTempDirectory("widget-log").toFile();
    }

    @Test
    public void emptyLogReadsAsEmpty() throws Exception {
        assertEquals("", new WidgetLog(directory()).readAll());
    }

    @Test
    public void lineHasTimeProcessAndMessage() throws Exception {
        WidgetLog log = new WidgetLog(directory());
        log.write(0L, 4242, "tap arrived");
        String text = log.readAll();
        assertTrue(text, text.matches("(?s)\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3} \\[4242] tap arrived\\n"));
    }

    @Test
    public void linesKeepTheirOrder() throws Exception {
        WidgetLog log = new WidgetLog(directory());
        log.write(1L, 1, "first");
        log.write(2L, 1, "second");
        String text = log.readAll();
        assertTrue(text.indexOf("first") < text.indexOf("second"));
    }

    @Test
    public void fullFileMovesToThePreviousOneAndNothingNewIsLost() throws Exception {
        File directory = directory();
        WidgetLog log = new WidgetLog(directory);
        String filler = "x".repeat(1000);
        int lines = WidgetLog.MAX_FILE_BYTES / 1000 + 20;
        for (int i = 0; i < lines; i++) log.write(i, 1, i + " " + filler);
        assertTrue(new File(directory, "arsound-widget-log.1.txt").isFile());
        String text = log.readAll();
        // The newest line is there, in order after the older ones, and the log did not grow past two files.
        assertTrue(text.contains((lines - 1) + " " + filler));
        assertTrue(text.length() <= 2L * WidgetLog.MAX_FILE_BYTES);
    }

    @Test
    public void oldestLinesGoAfterTwoFilesFill() throws Exception {
        WidgetLog log = new WidgetLog(directory());
        String filler = "y".repeat(1000);
        int lines = 3 * WidgetLog.MAX_FILE_BYTES / 1000;
        for (int i = 0; i < lines; i++) log.write(i, 1, "n" + i + "n " + filler);
        String text = log.readAll();
        assertFalse(text.contains("n0n "));
        assertTrue(text.contains("n" + (lines - 1) + "n "));
    }

    @Test
    public void constantNameFindsTheNameByPrefixAndValue() {
        assertEquals("THREE", WidgetDiagnostics.constantName(Constants.class, "START_REASON_", 3));
        assertEquals("COLD", WidgetDiagnostics.constantName(Constants.class, "START_TYPE_", 1));
        assertEquals("99", WidgetDiagnostics.constantName(Constants.class, "START_REASON_", 99));
    }

    /** Stands in for the system's class that has the constants. */
    public static final class Constants {
        public static final int START_REASON_ONE = 1;
        public static final int START_REASON_THREE = 3;
        public static final int START_TYPE_COLD = 1;
        public static final int STARTUP_STATE_STARTED = 3;
    }
}
