package app.revanced.extension.soundcloud.search;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A single hash-keyed entry from {@code player_configs.json}: a JS expression template for
 * deriving the signature ({@code sig}, with {@code INPUT} standing in for the ciphered
 * value), the class name used to n-transform ({@code nClass}), and the signature timestamp
 * ({@code sts}) YouTube's player API expects to be echoed back.
 */
final class PlayerConfig {
    final String sigJsExpression;
    final String nClass;
    final int sts;

    PlayerConfig(String sigJsExpression, String nClass, int sts) {
        this.sigJsExpression = sigJsExpression;
        this.nClass = nClass;
        this.sts = sts;
    }

    /**
     * The table is another project's file, read from the network, and its "sig" and "nClass" become part of script
     * that is run beside YouTube's player. So they must look as they do in every entry of the table: a call of one
     * function with numbers and the input, such as {@code Tl(48,5831,INPUT)}, and the plain name of a class.
     */
    private static final java.util.regex.Pattern SIG_SHAPE = java.util.regex.Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]{0,30}[(](?:[0-9]{1,9},[ ]?){0,4}INPUT(?:,[ ]?[0-9]{1,9}){0,4}[)]");
    private static final java.util.regex.Pattern N_CLASS_SHAPE = java.util.regex.Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]{0,30}");

    /**
     * Parses {@code player_configs.json}: {@code {"schemaVersion":1,"players":{"<hash>":
     * {"sig":"...","nClass":"...","sts":12345,"aliases":["<hash2>",...]}}}}. Each entry's
     * "sig" is a JS expression such as {@code Tl(48,5831,INPUT)}, matching innertubex's
     * {@code RemotePlayerConfigParser} expectations.
     */
    static Map<String, PlayerConfig> parseTable(String json) throws IOException {
        Object rootObj = new SimpleJsonParser(json).parse();
        if (!(rootObj instanceof Map)) {
            throw new IOException("player_configs.json root is not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) rootObj;

        Object schemaVersionObj = root.get("schemaVersion");
        if (!(schemaVersionObj instanceof Number) || ((Number) schemaVersionObj).intValue() != 1) {
            throw new IOException("Unsupported player_configs.json schemaVersion: " + schemaVersionObj);
        }

        Object playersObj = root.get("players");
        if (!(playersObj instanceof Map)) {
            throw new IOException("player_configs.json 'players' is not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> players = (Map<String, Object>) playersObj;

        Map<String, PlayerConfig> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : players.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) entry.getValue();

            Object sigObj = obj.get("sig");
            Object nClassObj = obj.get("nClass");
            Object stsObj = obj.get("sts");
            if (!(sigObj instanceof String) || !(nClassObj instanceof String) || !(stsObj instanceof Number)) {
                continue; // malformed entry: skip, mirroring RemotePlayerConfigParser's per-entry skip behavior
            }
            // Both are put into script that is run: only the shapes the table really has are taken. Anything else
            // is skipped like a malformed entry, and the track then fails to play instead of running that script.
            if (!SIG_SHAPE.matcher((String) sigObj).matches() || !N_CLASS_SHAPE.matcher((String) nClassObj).matches()) {
                continue;
            }

            PlayerConfig config = new PlayerConfig((String) sigObj, (String) nClassObj, ((Number) stsObj).intValue());
            result.put(entry.getKey(), config);

            Object aliasesObj = obj.get("aliases");
            if (aliasesObj instanceof List) {
                for (Object alias : (List<?>) aliasesObj) {
                    if (alias instanceof String) {
                        result.put((String) alias, config);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Builds the n-transform JS expression the same way innertubex's
     * {@code RemotePlayerConfigParser.buildNJsExpression} does: it instantiates the player's own
     * n-transform class ({@code nClass}) against a synthetic {@code googlevideo.com} URL and reads
     * back its {@code n} query parameter, since the class's real entry point operates on a whole
     * URL object rather than a bare string.
     */
    String nJsExpression() {
        return "(function(n){try{var u=new g." + nClass + "('https://x.googlevideo.com/videoplayback?n='+n,true);"
                + "var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)";
    }
}
