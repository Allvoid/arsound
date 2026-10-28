package app.revanced.extension.soundcloud.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class RedactedCopyTest {
    private static String copy(String text, long max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RedactedCopy.copy(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), out, max);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void everyLineEndsWithALineBreak() throws Exception {
        assertEquals("a\nb\n", copy("a\r\nb", 1000));
    }

    @Test
    public void secretsAreHiddenLineByLine() throws Exception {
        assertEquals("ok\nsc://auth?<redacted>\n", copy("ok\nsc://auth?code=1\n", 1000));
    }

    @Test
    public void russianTextSurvives() throws Exception {
        assertEquals("Ошибка входа\n", copy("Ошибка входа\n", 1000));
    }

    @Test
    public void copyStopsAtTheLimitWithANote() throws Exception {
        String result = copy("11111\n22222\n33333\n", 12);
        assertTrue(result.startsWith("11111\n22222\n[cut"));
        assertFalse(result.contains("33333"));
    }

    @Test
    public void emptyInputGivesNothing() throws Exception {
        assertEquals("", copy("", 1000));
    }
}
