package app.revanced.extension.soundcloud.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** The one strict JSON reader: the cases of the settings file's reader and of the picture service's reader, kept together. */
public class StrictJsonTest {
    /** The limit the settings file uses; the other user, the Commons answer, uses 40. */
    private static final int DEPTH = 8;

    private static Object parse(String text) throws StrictJson.Malformed {
        return StrictJson.parse(text, DEPTH);
    }

    private static void assertMalformed(String text) {
        try {
            parse(text);
            fail("accepted: " + text);
        } catch (StrictJson.Malformed expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static String quoted(String value) {
        StringBuilder out = new StringBuilder();
        StrictJson.quote(out, value);
        return out.toString();
    }

    private static String nested(int levels) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < levels; i++) text.append('[');
        for (int i = 0; i < levels; i++) text.append(']');
        return text.toString();
    }

    // region Reading

    @Test
    public void readsEveryKindOfValue() throws Exception {
        Object value = parse("{\"a\": [1, -2, 3.5, 1e2, true, false, null, \"x\"], \"b\": {}}");
        Map<String, Object> map = StrictJson.object(value);
        assertEquals(Arrays.asList(1L, -2L, 3.5, 100.0, true, false, null, "x"), map.get("a"));
        assertTrue(StrictJson.object(map.get("b")).isEmpty());
    }

    @Test
    public void readsObjectsArraysAndLeavesThroughTheHelpers() throws Exception {
        Object root = parse(" {\"a\": [1, 2.5, \"x\", true, false, null], \"b\": {\"c\": -7}} ");
        List<Object> a = StrictJson.array(StrictJson.get(root, "a"));
        assertNotNull(a);
        assertEquals(6, a.size());
        assertEquals(1L, a.get(0));
        assertEquals(2.5, (Double) a.get(1), 0.0);
        assertEquals("x", a.get(2));
        assertEquals(Boolean.TRUE, a.get(3));
        assertEquals(Boolean.FALSE, a.get(4));
        assertNull(a.get(5));
        assertEquals(-7L, StrictJson.number(StrictJson.get(StrictJson.get(root, "b"), "c"), 0));
    }

    @Test
    public void wholeNumbersAreLongAndTheOthersDouble() throws Exception {
        assertEquals(Long.valueOf(9007199254740993L), parse("9007199254740993"));
        assertEquals(Double.valueOf(1.0), parse("1.0"));
        assertEquals(Long.valueOf(0), parse("-0"));
    }

    @Test
    public void keepsTheOrderOfTheKeys() throws Exception {
        Map<String, Object> map = StrictJson.object(parse("{\"z\":1,\"a\":2,\"m\":3}"));
        assertEquals(Arrays.asList("z", "a", "m"), new ArrayList<>(map.keySet()));
        assertEquals("[z, a, m]", map.keySet().toString());
    }

    @Test
    public void readsEscapesAndUnicode() throws Exception {
        // The JSON text is: "a\"b\\c\/d\b\f\n\r\t\u042f"
        String json = "\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\\u042f\"";
        assertEquals("a\"b\\c/d\b\f\n\r\t" + (char) 0x042F, parse(json));
        assertEquals("é", parse("\"\\u00e9\""));
        assertEquals("Кот", parse("\"Кот\""));
        // A character outside the basic plane, written as it is and as two escapes (that come out as the one character).
        String note = new String(Character.toChars(0x1F3B5));
        assertEquals(note, parse("\"" + note + "\""));
        assertEquals(note, parse("\"\\ud83c\\udfb5\""));
        assertEquals("\uD83D\uDE00", parse("\"\\ud83d\\ude00\""));
    }

    @Test
    public void emptyContainersAreFine() throws Exception {
        assertTrue(StrictJson.object(parse("{}")).isEmpty());
        assertTrue(StrictJson.array(parse("[ ]")).isEmpty());
        assertTrue(StrictJson.array(parse("[]")).isEmpty());
    }

    @Test
    public void aByteOrderMarkInFrontIsSkipped() throws Exception {
        assertEquals(Long.valueOf(5), parse((char) 0xFEFF + " 5"));
    }

    @Test
    public void whitespaceAroundTheValueIsFine() throws Exception {
        assertEquals(Boolean.TRUE, parse(" \n\t true \r\n"));
    }

    @Test
    public void nullIsNull() throws Exception {
        assertNull(parse("null"));
    }

    // endregion

    // region Helpers

    @Test
    public void helpersGiveTheFallbackForAnythingElse() throws Exception {
        Object root = parse("{\"n\": 5, \"s\": \"12\", \"t\": \"text\", \"b\": true}");
        assertEquals("5", StrictJson.string(StrictJson.get(root, "n"), "?"));
        assertEquals("true", StrictJson.string(StrictJson.get(root, "b"), "?"));
        assertEquals("text", StrictJson.string(StrictJson.get(root, "t"), "?"));
        assertEquals("?", StrictJson.string(StrictJson.get(root, "missing"), "?"));
        assertEquals(12L, StrictJson.number(StrictJson.get(root, "s"), -1));
        assertEquals(5L, StrictJson.number(StrictJson.get(root, "n"), -1));
        assertEquals(-1L, StrictJson.number(StrictJson.get(root, "t"), -1));
        assertEquals(-1L, StrictJson.number(StrictJson.get(root, "missing"), -1));
        assertNull(StrictJson.get("not an object", "x"));
        assertNull(StrictJson.get(null, "x"));
        assertNull(StrictJson.object(parse("[1]")));
        assertNull(StrictJson.array(parse("{}")));
    }

    // endregion

    // region Quoting

    @Test
    public void quotingRoundTripsEveryKindOfText() throws Exception {
        String note = new String(Character.toChars(0x1F3B5));
        List<String> texts = Arrays.asList("", "plain", "say \"hi\"", "back\\slash", "line\nbreak\ttab\r",
                String.valueOf((char) 0) + (char) 0x1F, "Привет, мир", note + " note",
                "lone high " + (char) 0xD83C + " end", "lone low " + (char) 0xDFB5 + " end", "</script> & <b>");
        for (String text : texts) assertEquals(text, parse(quoted(text)));
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

    // endregion

    // region Refusing

    @Test
    public void refusesBrokenText() {
        String[] bad = {"", "   ", "{", "{\"a\":}", "{\"a\":", "{\"a\" 1}", "{a: 1}", "{'a': 1}", "{1:2}", "[1, 2", "[1 2]",
                "\"open", "tru", "nul", "-", "<html>captive portal</html>", "{\"a\":1} trailing", "\"bad \\q escape\"",
                "\"\\u12\""};
        for (String text : bad) assertMalformed(text);
    }

    @Test
    public void refusesWhatRfc8259Forbids() {
        assertMalformed("[1,]");
        assertMalformed("{\"a\":1,}");
        assertMalformed("01");
        assertMalformed("1.");
        assertMalformed(".5");
        assertMalformed("+1");
        assertMalformed("1e");
        assertMalformed("\"a\nb\"");
        assertMalformed("\"bad \\x escape\"");
        assertMalformed("\"short \\u12\"");
        assertMalformed("\"bad \\uZZZZ\"");
        assertMalformed("// comment\n1");
    }

    @Test
    public void aUnicodeEscapeTakesOnlyTheDigitsJsonNames() {
        // Full-width digits are digits to Character.digit, but not to JSON.
        assertMalformed("\"\\u\uFF10\uFF10\uFF14\uFF11\"");
    }

    @Test
    public void refusesAnythingAfterTheValue() {
        assertMalformed("{} {}");
        assertMalformed("1 2");
        assertMalformed("{}x");
    }

    @Test
    public void refusesANumberTooBigForALong() {
        assertMalformed("9223372036854775808");
    }

    @Test
    public void aMissingTextIsRefused() {
        try {
            StrictJson.parse(null, DEPTH);
            fail();
        } catch (StrictJson.Malformed expected) {
            // As it should be.
        }
    }

    @Test
    public void theErrorIsAnIoExceptionThatSaysWhereTheProblemIs() {
        try {
            parse("{\"a\": 1,}");
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(" at "));
        }
    }

    @Test
    public void nestingIsLimitedByTheCaller() throws Exception {
        // The outermost array is level 0, so a limit of 8 allows nine of them inside each other.
        parse(nested(DEPTH + 1));
        assertMalformed(nested(DEPTH + 2));
        StrictJson.parse(nested(40 + 1), 40);
        try {
            StrictJson.parse(nested(40 + 2), 40);
            fail();
        } catch (StrictJson.Malformed expected) {
            // As it should be.
        }
    }

    @Test
    public void tooDeepNestingIsRefusedBeforeItFillsTheStack() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100_000; i++) deep.append('[');
        assertMalformed(deep.toString());
        try {
            StrictJson.parse(deep.toString(), 40);
            fail();
        } catch (StrictJson.Malformed expected) {
            // As it should be.
        }
    }

    // endregion
}
