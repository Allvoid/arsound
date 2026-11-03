package app.revanced.extension.soundcloud.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small strict JSON reader and the quoting the writer of the settings file needs.
 * <p>
 * Written here instead of using {@code org.json} because the file format is tested on the JVM, where Android's JSON
 * classes are only empty stubs. It reads exactly what RFC 8259 allows (no comments, no trailing commas, nothing after the
 * value) so a damaged or foreign file is refused, not half read. Objects come back as {@link LinkedHashMap}, arrays as
 * {@link ArrayList}, numbers without a fraction or exponent as {@link Long}, other numbers as {@link Double}, plus
 * {@link String}, {@link Boolean} and null. Pure Java, no Android.
 */
final class TransferJson {
    /** Objects and arrays inside each other: a settings file needs four levels, so a deeper one is not ours. */
    static final int MAX_DEPTH = 8;

    private final String text;
    private int pos;

    private TransferJson(String text) {
        this.text = text;
    }

    /**
     * The value the text holds.
     *
     * @throws TransferException {@link TransferException.Reason#NOT_JSON} for anything that is not exactly one JSON value
     */
    static Object parse(String text) throws TransferException {
        // A text editor on Windows puts a byte order mark in front of the text.
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        TransferJson reader = new TransferJson(text);
        reader.skipSpace();
        Object value = reader.value(0);
        reader.skipSpace();
        if (reader.pos != text.length()) throw reader.fail("text after the end");
        return value;
    }

    /** Appends the text in quotes with everything that needs it escaped; a half of a surrogate pair too, to keep it. */
    static void quote(StringBuilder out, String value) {
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

    private Object value(int depth) throws TransferException {
        if (depth > MAX_DEPTH) throw fail("nested too deep");
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

    private Map<String, Object> object(int depth) throws TransferException {
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

    private List<Object> array(int depth) throws TransferException {
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

    private String string() throws TransferException {
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
                    result.append(unicode());
                    break;
                default:
                    throw fail("unknown escape \\" + escape);
            }
        }
        throw fail("a string is not closed");
    }

    private char unicode() throws TransferException {
        if (pos + 4 > text.length()) throw fail("a short \\u escape");
        int code = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(pos + i), 16);
            if (digit < 0) throw fail("a bad \\u escape");
            code = code * 16 + digit;
        }
        pos += 4;
        return (char) code;
    }

    /** The grammar of RFC 8259: -?(0|[1-9][0-9]*)(.[0-9]+)?([eE][+-]?[0-9]+)? */
    private Object number() throws TransferException {
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

    private void word(String word) throws TransferException {
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

    private TransferException fail(String problem) {
        return new TransferException(TransferException.Reason.NOT_JSON, problem + " at " + pos);
    }
}
