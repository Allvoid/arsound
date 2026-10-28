package app.revanced.extension.soundcloud.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

public class LogComposerTest {
    /** A part with a fixed text; the context is not used by any part in these tests. */
    private static final class Fixed implements LogSection {
        final String name;
        final boolean record;
        final boolean content;
        final String text;
        boolean failsWhenWritten;

        Fixed(String name, boolean record, boolean content, String text) {
            this.name = name;
            this.record = record;
            this.content = content;
            this.text = text;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isRecord() {
            return record;
        }

        @Override
        public boolean hasContent(Context context) {
            return content;
        }

        @Override
        public void writeTo(Context context, OutputStream out) throws Exception {
            if (failsWhenWritten) throw new IllegalStateException("broken");
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String write(LogSection... sections) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new LogComposer(Arrays.asList(sections)).write(null, out);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void partsAreWrittenInOrderWithTheirHeadings() throws Exception {
        String text = write(new Fixed(null, false, true, "header\n"), new Fixed("one", true, true, "1\n"),
                new Fixed("two", true, true, "2\n"));
        assertEquals("header\n\n==== one ====\n1\n\n==== two ====\n2\n", text);
    }

    @Test
    public void partsWithoutContentAreLeftOut() throws Exception {
        assertEquals("\n==== two ====\n2\n",
                write(new Fixed("one", true, false, "1\n"), new Fixed("two", true, true, "2\n")));
    }

    @Test
    public void unreadablePartLeavesALineAndTheRestIsStillWritten() throws Exception {
        Fixed broken = new Fixed("one", true, true, "1\n");
        broken.failsWhenWritten = true;
        String text = write(broken, new Fixed("two", true, true, "2\n"));
        assertTrue(text.contains("==== one: could not be written, java.lang.IllegalStateException: broken"));
        assertTrue(text.endsWith("\n==== two ====\n2\n"));
    }

    @Test(expected = IOException.class)
    public void failingOutputIsReportedToTheCaller() throws Exception {
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }
        };
        new LogComposer(Arrays.<LogSection>asList(new Fixed("one", true, true, "1\n"))).write(null, broken);
    }

    @Test
    public void onlyEarlierRecordsCountAsLogs() {
        LogComposer snapshotsOnly = new LogComposer(Arrays.<LogSection>asList(
                new Fixed(null, false, true, "h"), new Fixed("env", false, true, "e"),
                new Fixed("crashes", true, false, "")));
        assertFalse(snapshotsOnly.hasRecords(null));

        LogComposer withRecord = new LogComposer(Arrays.<LogSection>asList(
                new Fixed(null, false, true, "h"), new Fixed("playback", true, true, "p")));
        assertTrue(withRecord.hasRecords(null));
    }
}
