package app.revanced.extension.soundcloud.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal recursive-descent JSON object/array parser sufficient for player_configs.json's
 * shape (nested objects, arrays of strings, string/number/boolean/null leaves). Avoids pulling
 * in a JSON dependency for this single call site.
 */
final class SimpleJsonParser {
    private final String s;
    private int pos;

    SimpleJsonParser(String s) {
        this.s = s;
    }

    Object parse() throws IOException {
        skipWhitespace();
        return parseValue();
    }

    private Object parseValue() throws IOException {
        skipWhitespace();
        if (pos >= s.length()) {
            throw new IOException("Unexpected end of JSON");
        }
        char c = s.charAt(pos);
        if (c == '{') {
            return parseObject();
        } else if (c == '[') {
            return parseArray();
        } else if (c == '"') {
            return parseString();
        } else if (c == 'n' && s.startsWith("null", pos)) {
            pos += 4;
            return null;
        } else if (c == 't' && s.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        } else if (c == 'f' && s.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        } else {
            return parseNumber();
        }
    }

    private Map<String, Object> parseObject() throws IOException {
        Map<String, Object> map = new HashMap<>();
        pos++; // '{'
        skipWhitespace();
        if (pos < s.length() && s.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            if (pos >= s.length() || s.charAt(pos) != ':') {
                throw new IOException("Expected ':' in JSON object");
            }
            pos++;
            Object value = parseValue();
            map.put(key, value);
            skipWhitespace();
            if (pos >= s.length()) {
                throw new IOException("Unterminated JSON object");
            }
            char c = s.charAt(pos);
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                break;
            } else {
                throw new IOException("Malformed JSON object");
            }
        }
        return map;
    }

    private List<Object> parseArray() throws IOException {
        List<Object> list = new ArrayList<>();
        pos++; // '['
        skipWhitespace();
        if (pos < s.length() && s.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(parseValue());
            skipWhitespace();
            if (pos >= s.length()) {
                throw new IOException("Unterminated JSON array");
            }
            char c = s.charAt(pos);
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                break;
            } else {
                throw new IOException("Malformed JSON array");
            }
        }
        return list;
    }

    private String parseString() throws IOException {
        if (pos >= s.length() || s.charAt(pos) != '"') {
            throw new IOException("Expected string in JSON");
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= s.length()) {
                    break;
                }
                char esc = s.charAt(pos++);
                switch (esc) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'u':
                        String hex = s.substring(pos, pos + 4);
                        pos += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                        break;
                    default:
                        sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IOException("Unterminated JSON string");
    }

    private Number parseNumber() {
        int start = pos;
        while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || "+-.eE".indexOf(s.charAt(pos)) >= 0)) {
            pos++;
        }
        String numStr = s.substring(start, pos);
        if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
            return Double.parseDouble(numStr);
        }
        return Long.parseLong(numStr);
    }

    private void skipWhitespace() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }
}
