package app.revanced.extension.soundcloud.search;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The data BotGuard sends and expects: the challenge and the integrity token as JSON arrays, bytes as JavaScript
 * {@code Uint8Array} literals, and YouTube's flavour of base64 (ported from JavaScriptUtil.kt). The responses are
 * simple fixed-shape arrays, so a small parser reads them. Pure, no Android.
 */
final class BotGuardData {
    private BotGuardData() {
    }

    static final class IntegrityTokenData {
        final String integrityTokenU8Array;
        final long expirationTimeSeconds;

        IntegrityTokenData(String integrityTokenU8Array, long expirationTimeSeconds) {
            this.integrityTokenU8Array = integrityTokenU8Array;
            this.expirationTimeSeconds = expirationTimeSeconds;
        }
    }

    /**
     * Parses the raw challenge data obtained from the Create endpoint and returns a JS object
     * literal (as source text) that can be embedded directly in an evaluated JS snippet.
     */
    static String parseChallengeData(String rawChallengeData) throws PoTokenWebView.PoTokenException {
        Object[] scrambled = JsonArrayParser.parseArray(rawChallengeData);

        Object[] challengeData;
        if (scrambled.length > 1 && scrambled[1] instanceof String) {
            String descrambled = descramble((String) scrambled[1]);
            challengeData = JsonArrayParser.parseArray(descrambled);
        } else {
            challengeData = JsonArrayParser.asArray(scrambled[0]);
        }

        String messageId = String.valueOf(challengeData[0]);
        String interpreterHash = String.valueOf(challengeData[3]);
        String program = String.valueOf(challengeData[4]);
        String globalName = String.valueOf(challengeData[5]);
        String clientExperimentsStateBlob = String.valueOf(challengeData[7]);

        String safeScriptValue = findFirstString(challengeData[1]);
        String trustedResourceUrlValue = findFirstString(challengeData[2]);

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"messageId\":").append(jsonQuote(messageId)).append(",");
        sb.append("\"interpreterJavascript\":{");
        sb.append("\"privateDoNotAccessOrElseSafeScriptWrappedValue\":")
                .append(safeScriptValue == null ? "null" : jsonQuote(safeScriptValue)).append(",");
        sb.append("\"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue\":")
                .append(trustedResourceUrlValue == null ? "null" : jsonQuote(trustedResourceUrlValue));
        sb.append("},");
        sb.append("\"interpreterHash\":").append(jsonQuote(interpreterHash)).append(",");
        sb.append("\"program\":").append(jsonQuote(program)).append(",");
        sb.append("\"globalName\":").append(jsonQuote(globalName)).append(",");
        sb.append("\"clientExperimentsStateBlob\":").append(jsonQuote(clientExperimentsStateBlob));
        sb.append("}");
        return sb.toString();
    }

    /**
     * Parses the raw integrity token data obtained from the GenerateIT endpoint into a JS
     * {@code Uint8Array} literal (as source text) plus the token's lifetime in seconds.
     */
    static IntegrityTokenData parseIntegrityTokenData(String rawIntegrityTokenData) throws PoTokenWebView.PoTokenException {
        Object[] data = JsonArrayParser.parseArray(rawIntegrityTokenData);
        String integrityTokenU8Array = base64ToU8((String) data[0]);
        long expirationTimeSeconds = ((Number) data[1]).longValue();
        return new IntegrityTokenData(integrityTokenU8Array, expirationTimeSeconds);
    }

    /** Converts a string (the identifier passed to {@code obtainPoToken}) to a JS Uint8Array literal. */
    static String stringToU8(String identifier) {
        return newUint8Array(identifier.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Takes a poToken encoded as comma separated byte values (the output of Uint8Array::toString()
     * in JavaScript) and converts it to poToken's specific base64 representation.
     */
    static String u8ToBase64(String poToken) {
        String[] parts = poToken.split(",");
        byte[] bytes = new byte[parts.length == 1 && parts[0].isEmpty() ? 0 : parts.length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (Integer.parseInt(parts[i].trim()) & 0xFF);
        }
        String base64 = Base64.getEncoder().withoutPadding().encodeToString(bytes);
        return base64.replace('+', '-').replace('/', '_');
    }

    /** Decodes the scrambled challenge: base64-decode, then add 97 to each byte. */
    private static String descramble(String scrambledChallenge) throws PoTokenWebView.PoTokenException {
        byte[] decoded = base64ToByteArray(scrambledChallenge);
        byte[] result = new byte[decoded.length];
        for (int i = 0; i < decoded.length; i++) {
            result[i] = (byte) (decoded[i] + 97);
        }
        return new String(result, StandardCharsets.UTF_8);
    }

    /** Decodes a YouTube-flavored base64 string into a JS Uint8Array literal (as source text). */
    private static String base64ToU8(String base64) throws PoTokenWebView.PoTokenException {
        return newUint8Array(base64ToByteArray(base64));
    }

    private static String newUint8Array(byte[] contents) {
        StringBuilder sb = new StringBuilder("new Uint8Array([");
        for (int i = 0; i < contents.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(contents[i] & 0xFF);
        }
        sb.append("])");
        return sb.toString();
    }

    /** Decodes a base64 string encoded in the specific base64 representation used by YouTube. */
    private static byte[] base64ToByteArray(String base64) throws PoTokenWebView.PoTokenException {
        String base64Mod = base64.replace('-', '+').replace('_', '/').replace('.', '=');
        try {
            return Base64.getDecoder().decode(base64Mod);
        } catch (IllegalArgumentException e) {
            throw new PoTokenWebView.PoTokenException("Cannot base64 decode");
        }
    }

    private static String findFirstString(Object arrayOrNull) {
        if (!(arrayOrNull instanceof Object[])) {
            return null;
        }
        for (Object o : (Object[]) arrayOrNull) {
            if (o instanceof String) {
                return (String) o;
            }
        }
        return null;
    }

    private static String jsonQuote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * A minimal recursive-descent JSON array parser: the botguard Create/GenerateIT responses are
     * simple (possibly nested) JSON arrays of strings/numbers/nulls, so a full JSON library is
     * unnecessary here.
     */
    private static final class JsonArrayParser {
        private final String s;
        private int pos;

        private JsonArrayParser(String s) {
            this.s = s;
        }

        static Object[] parseArray(String json) throws PoTokenWebView.PoTokenException {
            JsonArrayParser parser = new JsonArrayParser(json.trim());
            Object value = parser.parseValue();
            return asArray(value);
        }

        static Object[] asArray(Object value) throws PoTokenWebView.PoTokenException {
            if (!(value instanceof Object[])) {
                throw new PoTokenWebView.PoTokenException("Expected JSON array");
            }
            return (Object[]) value;
        }

        private Object parseValue() throws PoTokenWebView.PoTokenException {
            skipWhitespace();
            if (pos >= s.length()) {
                throw new PoTokenWebView.PoTokenException("Unexpected end of JSON");
            }
            char c = s.charAt(pos);
            if (c == '[') {
                return parseArrayValue();
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

        private Object[] parseArrayValue() throws PoTokenWebView.PoTokenException {
            List<Object> list = new ArrayList<>();
            pos++; // consume '['
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list.toArray();
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (pos >= s.length()) {
                    throw new PoTokenWebView.PoTokenException("Unterminated JSON array");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    break;
                } else {
                    throw new PoTokenWebView.PoTokenException("Malformed JSON array");
                }
            }
            return list.toArray();
        }

        private String parseString() throws PoTokenWebView.PoTokenException {
            pos++; // consume opening quote
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
            throw new PoTokenWebView.PoTokenException("Unterminated JSON string");
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
}
