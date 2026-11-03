package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TransferJsonTest {
    private static void assertNotJson(String text) {
        try {
            TransferJson.parse(text);
            fail("accepted: " + text);
        } catch (TransferException ex) {
            assertEquals(TransferException.Reason.NOT_JSON, ex.reason);
        }
    }

    private static String quoted(String value) {
        StringBuilder out = new StringBuilder();
        TransferJson.quote(out, value);
        return out.toString();
    }

    @Test
    public void readsEveryKindOfValue() throws Exception {
        Object value = TransferJson.parse("{\"a\": [1, -2, 3.5, 1e2, true, false, null, \"x\"], \"b\": {}}");
        @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) value;
        assertEquals(Arrays.asList(1L, -2L, 3.5, 100.0, true, false, null, "x"), map.get("a"));
        assertTrue(((Map<?, ?>) map.get("b")).isEmpty());
    }

    @Test
    public void wholeNumbersAreLongAndTheOthersDouble() throws Exception {
        assertEquals(Long.valueOf(9007199254740993L), TransferJson.parse("9007199254740993"));
        assertEquals(Double.valueOf(1.0), TransferJson.parse("1.0"));
        assertEquals(Long.valueOf(0), TransferJson.parse("-0"));
    }

    @Test
    public void keepsTheOrderOfTheKeys() throws Exception {
        @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) TransferJson.parse("{\"z\":1,\"a\":2,\"m\":3}");
        assertEquals(Arrays.asList("z", "a", "m"), new ArrayList<>(map.keySet()));
    }

    @Test
    public void readsEscapesAndUnicode() throws Exception {
        // The JSON text is: "a\"b\\c\/d\b\f\n\r\t\u042f"
        String json = "\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\\u042f\"";
        assertEquals("a\"b\\c/d\b\f\n\r\t" + (char) 0x042F, TransferJson.parse(json));
        // A character outside the basic plane, written as it is and as two escapes.
        String note = new String(Character.toChars(0x1F3B5));
        assertEquals(note, TransferJson.parse("\"" + note + "\""));
        assertEquals(note, TransferJson.parse("\"\\ud83c\\udfb5\""));
    }

    @Test
    public void aByteOrderMarkInFrontIsSkipped() throws Exception {
        assertEquals(Long.valueOf(5), TransferJson.parse((char) 0xFEFF + " 5"));
    }

    @Test
    public void whitespaceAroundTheValueIsFine() throws Exception {
        assertEquals(Boolean.TRUE, TransferJson.parse(" \n\t true \r\n"));
    }

    @Test
    public void quotingRoundTripsEveryKindOfText() throws Exception {
        String note = new String(Character.toChars(0x1F3B5));
        List<String> texts = Arrays.asList("", "plain", "say \"hi\"", "back\\slash", "line\nbreak\ttab\r",
                String.valueOf((char) 0) + (char) 0x1F, "Привет, мир", note + " note",
                "lone high " + (char) 0xD83C + " end", "lone low " + (char) 0xDFB5 + " end", "</script> & <b>");
        for (String text : texts) assertEquals(text, TransferJson.parse(quoted(text)));
    }

    @Test
    public void quotingEscapesWhatJsonDemands() {
        // a"b\c<newline><SOH>  ->  "a\"b\\c\n\u0001"
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", quoted("a\"b\\c\n" + (char) 1));
    }

    @Test
    public void aPairOfSurrogatesIsWrittenAsItIsAndALoneOneEscaped() {
        String note = new String(Character.toChars(0x1F3B5));
        assertEquals("\"" + note + "\"", quoted(note));
        assertEquals("\"\\ud83c\"", quoted(String.valueOf((char) 0xD83C)));
        assertEquals("\"\\udfb5\"", quoted(String.valueOf((char) 0xDFB5)));
    }

    @Test
    public void refusesBrokenText() {
        assertNotJson("");
        assertNotJson("   ");
        assertNotJson("{");
        assertNotJson("{\"a\":}");
        assertNotJson("{\"a\" 1}");
        assertNotJson("{a: 1}");
        assertNotJson("{'a': 1}");
        assertNotJson("[1, 2");
        assertNotJson("[1 2]");
        assertNotJson("\"open");
        assertNotJson("tru");
        assertNotJson("nul");
    }

    @Test
    public void refusesWhatRfc8259Forbids() {
        assertNotJson("[1,]");
        assertNotJson("{\"a\":1,}");
        assertNotJson("01");
        assertNotJson("1.");
        assertNotJson(".5");
        assertNotJson("+1");
        assertNotJson("1e");
        assertNotJson("\"a\nb\"");
        assertNotJson("\"bad \\x escape\"");
        assertNotJson("\"short \\u12\"");
        assertNotJson("\"bad \\uZZZZ\"");
        assertNotJson("// comment\n1");
    }

    @Test
    public void refusesAnythingAfterTheValue() {
        assertNotJson("{} {}");
        assertNotJson("1 2");
        assertNotJson("{}x");
    }

    @Test
    public void refusesANumberTooBigForALong() {
        assertNotJson("9223372036854775808");
    }

    @Test
    public void refusesTextNestedTooDeep() throws Exception {
        StringBuilder fine = new StringBuilder();
        for (int i = 0; i <= TransferJson.MAX_DEPTH; i++) fine.append('[');
        for (int i = 0; i <= TransferJson.MAX_DEPTH; i++) fine.append(']');
        TransferJson.parse(fine.toString());

        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100_000; i++) deep.append('[');
        assertNotJson(deep.toString());
    }

    @Test
    public void nullIsNull() throws Exception {
        assertNull(TransferJson.parse("null"));
    }
}
