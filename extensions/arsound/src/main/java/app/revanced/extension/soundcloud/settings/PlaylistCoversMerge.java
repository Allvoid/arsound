package app.revanced.extension.soundcloud.settings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import app.revanced.extension.soundcloud.shared.StrictJson;

/**
 * The covers the user set on playlists, in the settings file: one string value {@code playlist_covers} of the file
 * {@code arsound_local_covers}, a JSON object of playlist urn to the picture (base64 of a small JPEG). It is not a real
 * preference: {@code TransferFiles} makes it from the cover files when exporting and sets the covers from it when
 * importing. Coming back, the covers of the file replace those of the same playlists; covers of other playlists stay.
 * <p>
 * Pure, so a hand-edited or hostile file is refused here and not at the disk: the urn must look like one, the picture
 * must be base64, one picture and the whole value are bounded.
 */
final class PlaylistCoversMerge implements TransferPlan.Merge {
    /** The one key of the file {@code arsound_local_covers} that travels. */
    static final String KEY = "playlist_covers";

    private static final Pattern URN = Pattern.compile("[A-Za-z0-9_:.@~+-]{1,200}");
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/=\\r\\n]+");
    private static final int MAX_COVERS = 400;
    private static final int MAX_PICTURE_CHARS = 400_000;
    private static final int MAX_TOTAL_CHARS = 3_000_000;

    @Override
    public Object outgoing(Object here) {
        Map<String, String> covers = read(here);
        return covers == null || covers.isEmpty() ? null : write(covers);
    }

    @Override
    public Object incoming(Object fromFile, Object here) {
        Map<String, String> covers = read(fromFile);
        return covers == null || covers.isEmpty() ? null : write(covers);
    }

    /** The covers of a stored value, urn to base64; null when the value is not such an object. Bad entries are dropped. */
    static Map<String, String> read(Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty()) return null;
        Object root;
        try {
            root = StrictJson.parse((String) value, 2);
        } catch (Exception ex) {
            return null;
        }
        if (!(root instanceof Map)) return null;
        Map<String, String> covers = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) root).entrySet()) {
            if (covers.size() >= MAX_COVERS) break;
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) continue;
            String urn = (String) entry.getKey();
            String picture = (String) entry.getValue();
            if (!URN.matcher(urn).matches() || picture.isEmpty() || picture.length() > MAX_PICTURE_CHARS
                    || !BASE64.matcher(picture).matches() || total + picture.length() > MAX_TOTAL_CHARS) continue;
            total += picture.length();
            covers.put(urn, picture);
        }
        return covers;
    }

    /** The JSON text of covers. */
    static String write(Map<String, String> covers) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> cover : covers.entrySet()) {
            if (!first) out.append(',');
            first = false;
            StrictJson.quote(out, cover.getKey());
            out.append(':');
            StrictJson.quote(out, cover.getValue());
        }
        return out.append('}').toString();
    }
}
