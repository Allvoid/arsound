package app.revanced.extension.soundcloud.shared;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one strict JSON reader of the mod, and the quoting a JSON writer needs. Written here instead of using
 * {@code org.json} because the formats that use it (the settings file, the answer of a picture service) are tested on
 * the JVM, where Android's JSON classes are only empty stubs. Pure Java, no Android and no library.
 * <p>
 * It reads exactly what RFC 8259 allows (no comments, no trailing commas, no leading zeros, no raw control character in a
 * string, nothing after the value), so a damaged or foreign text is refused, not half read. A text that comes from
 * outside (a file the user picked, a reply of a proxy or a captive portal) must fail as a plain {@link Malformed}
 * exception, never as a crash, and nesting is limited by the caller because a hostile text would otherwise fill the stack.
 * <p>
 * Objects come back as {@link LinkedHashMap} (the order of the text is kept), arrays as {@link ArrayList}, numbers without
 * a fraction or exponent as {@link Long}, other numbers as {@link Double}, plus {@link String}, {@link Boolean} and null.
 * The helpers {@link #object}, {@link #array}, {@link #get}, {@link #string} and {@link #number} read such a tree without
 * a cast and give a fallback instead of failing for a value of another kind.
 */
public final class StrictJson {
    /** A text that is not exactly one JSON value. It is an {@link IOException}, so a reader of a network answer can let it through. */
    public static final class Malformed extends IOException {
        Malformed(String message) {
            super(message);
        }
    }

    private final String text;
    private final int maxDepth;
    private int pos;

    private StrictJson(String text, int maxDepth) {
        this.text = text;
        this.maxDepth = maxDepth;
    }

    /**
     * The value the text holds.
     *
     * @param text     The JSON; a byte order mark in front of it is skipped (a text editor on Windows puts one there).
     * @param maxDepth How deep objects and arrays may stand inside each other; the outermost one is level 0. Each caller
     *                 chooses what its own format needs, so a deeper text is refused instead of filling the stack.
     * @throws Malformed For anything that is not exactly one JSON value, null included. The message names the problem and
     *                   the place in the text.
     */
    public static Object parse(String text, int maxDepth) throws Malformed {
        if (text == null) throw new Malformed("no text");
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        StrictJson reader = new StrictJson(text, maxDepth);
        reader.skipSpace();
        Object value = reader.value(0);
        reader.skipSpace();
        if (reader.pos != text.length()) throw reader.fail("text after the end");
        return value;
    }

    /** Appends the text in quotes with everything that needs it escaped; a half of a surrogate pair too, to keep it. */
    public static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') out.append("\\\"");
            else if (c == '\\') out.append("\\\\");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                out.append(c).append(value.charAt(++i));
            } else if (Character.isSurrogate(c)) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        out.append('"');
    }

    // region Reading helpers

    /** The object a value is, or null. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** The array a value is, or null. */
    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    /** The member of an object, or null when the value is no object or has no such member. */
    public static Object get(Object object, String key) {
        Map<String, Object> map = object(object);
        return map == null ? null : map.get(key);
    }

    /** A string; a number or a boolean is written out; anything else gives the fallback. */
    public static String string(Object value, String fallback) {
        if (value instanceof String) return (String) value;
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return fallback;
    }

    /** A whole number; a string of digits counts too; anything else gives the fallback. */
    public static long number(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }
        return fallback;
    }

    // endregion

    // region Reader

    private Object value(int depth) throws Malformed {
        if (depth > maxDepth) throw fail("nested too deep");
        if (pos >= text.length()) throw fail("unexpected end");
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                word("true");
                return Boolean.TRUE;
            case 'f':
                word("false");
                return Boolean.FALSE;
            case 'n':
                word("null");
                return null;
            default:
                return number();
        }
    }

    private Map<String, Object> object(int depth) throws Malformed {
        Map<String, Object> result = new LinkedHashMap<>();
        pos++;
        skipSpace();
        if (take('}')) return result;
        while (true) {
            skipSpace();
            if (pos >= text.length() || text.charAt(pos) != '"') throw fail("a name was expected");
            String name = string();
            skipSpace();
            if (!take(':')) throw fail("':' was expected");
            skipSpace();
            result.put(name, value(depth + 1));
            skipSpace();
            if (take('}')) return result;
            if (!take(',')) throw fail("',' or '}' was expected");
        }
    }

    private List<Object> array(int depth) throws Malformed {
        List<Object> result = new ArrayList<>();
        pos++;
        skipSpace();
        if (take(']')) return result;
        while (true) {
            skipSpace();
            result.add(value(depth + 1));
            skipSpace();
            if (take(']')) return result;
            if (!take(',')) throw fail("',' or ']' was expected");
        }
    }

    private String string() throws Malformed {
        pos++;
        StringBuilder result = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') return result.toString();
            if (c < 0x20) throw fail("a raw control character in a string");
            if (c != '\\') {
                result.append(c);
                continue;
            }
            if (pos >= text.length()) break;
            char escape = text.charAt(pos++);
            switch (escape) {
                case '"':
                case '\\':
                case '/':
                    result.append(escape);
                    break;
                case 'b':
                    result.append('\b');
                    break;
                case 'f':
                    result.append('\f');
                    break;
                case 'n':
                    result.append('\n');
                    break;
                case 'r':
                    result.append('\r');
                    break;
                case 't':
                    result.append('\t');
                    break;
                case 'u':
                    // A pair of surrogates written one after the other comes out as one character by itself.
                    result.append(unicode());
                    break;
                default:
                    throw fail("unknown escape \\" + escape);
            }
        }
        throw fail("a string is not closed");
    }

    private char unicode() throws Malformed {
        if (pos + 4 > text.length()) throw fail("a short \\u escape");
        int code = 0;
        for (int i = 0; i < 4; i++) {
            // Only the digits JSON names: Character.digit would also take the full-width digits of other scripts.
            int digit = hexDigit(text.charAt(pos + i));
            if (digit < 0) throw fail("a bad \\u escape");
            code = code * 16 + digit;
        }
        pos += 4;
        return (char) code;
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /** The grammar of RFC 8259: -?(0|[1-9][0-9]*)(.[0-9]+)?([eE][+-]?[0-9]+)? */
    private Object number() throws Malformed {
        int start = pos;
        take('-');
        if (take('0')) {
            // No leading zeros: "01" is not a number.
        } else if (!digits()) {
            throw fail("a value was expected");
        }
        boolean whole = true;
        if (take('.')) {
            whole = false;
            if (!digits()) throw fail("digits were expected after '.'");
        }
        if (take('e') || take('E')) {
            whole = false;
            if (!take('+')) take('-');
            if (!digits()) throw fail("digits were expected in the exponent");
        }
        String literal = text.substring(start, pos);
        try {
            return whole ? (Object) Long.parseLong(literal) : (Object) Double.parseDouble(literal);
        } catch (NumberFormatException ex) {
            throw fail("a number out of range");
        }
    }

    /** True if at least one digit was read. */
    private boolean digits() {
        int start = pos;
        while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') pos++;
        return pos > start;
    }

    private void word(String word) throws Malformed {
        if (!text.startsWith(word, pos)) throw fail("a value was expected");
        pos += word.length();
    }

    private boolean take(char c) {
        if (pos < text.length() && text.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void skipSpace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            pos++;
        }
    }

    private Malformed fail(String problem) {
        return new Malformed(problem + " at " + pos);
    }

    // endregion
}
