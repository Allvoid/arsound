package app.revanced.extension.soundcloud.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.revanced.extension.soundcloud.shared.StrictJson;

/**
 * The tracks added to playlists on this phone ({@code additions} of {@code arsound_local_additions}: a JSON object of
 * playlist urn to its entries) in the settings file. A SoundCloud track ({@code soundcloud:tracks:...}) is the user's
 * choice and travels; an imported file ({@code file:...}) is a file of this phone and does not.
 * <p>
 * Coming back, the SoundCloud tracks of the file take the place of the phone's SoundCloud tracks of each playlist, and
 * the phone's own files stay, after them, in their order. A playlist the file does not name keeps what it has.
 */
final class AdditionsMerge implements TransferPlan.Merge {
    private static final String FILE_PREFIX = "file:";
    private static final int MAX_DEPTH = 4;

    @Override
    public Object outgoing(Object here) {
        Map<String, List<String>> additions = read(here);
        if (additions == null) return null;
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> playlist : additions.entrySet()) {
            List<String> tracks = new ArrayList<>();
            for (String entry : playlist.getValue()) if (!entry.startsWith(FILE_PREFIX)) tracks.add(entry);
            if (!tracks.isEmpty()) result.put(playlist.getKey(), tracks);
        }
        return result.isEmpty() ? null : write(result);
    }

    @Override
    public Object incoming(Object fromFile, Object here) {
        Map<String, List<String>> coming = read(fromFile);
        if (coming == null) return null;
        Map<String, List<String>> current = read(here);
        Map<String, List<String>> result = current == null ? new LinkedHashMap<>() : current;
        for (Map.Entry<String, List<String>> playlist : coming.entrySet()) {
            Set<String> merged = new LinkedHashSet<>();
            for (String entry : playlist.getValue()) if (!entry.startsWith(FILE_PREFIX)) merged.add(entry);
            List<String> old = result.get(playlist.getKey());
            if (old != null) for (String entry : old) if (entry.startsWith(FILE_PREFIX)) merged.add(entry);
            result.put(playlist.getKey(), new ArrayList<>(merged));
        }
        return write(result);
    }

    /** The additions of a stored value; null for a value that is not such an object. */
    static Map<String, List<String>> read(Object value) {
        if (!(value instanceof String)) return null;
        Object root;
        try {
            root = StrictJson.parse((String) value, MAX_DEPTH);
        } catch (Exception ex) {
            return null;
        }
        if (!(root instanceof Map)) return null;
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> playlist : ((Map<?, ?>) root).entrySet()) {
            if (!(playlist.getValue() instanceof List)) continue;
            List<String> entries = new ArrayList<>();
            for (Object entry : (List<?>) playlist.getValue()) if (entry instanceof String) entries.add((String) entry);
            result.put(String.valueOf(playlist.getKey()), entries);
        }
        return result;
    }

    /** The JSON the additions store reads: {@code {"urn":["entry",...],...}}, empty playlists left out. */
    static String write(Map<String, List<String>> additions) {
        StringBuilder out = new StringBuilder("{");
        boolean firstPlaylist = true;
        for (Map.Entry<String, List<String>> playlist : additions.entrySet()) {
            if (playlist.getValue().isEmpty()) continue;
            if (!firstPlaylist) out.append(',');
            firstPlaylist = false;
            StrictJson.quote(out, playlist.getKey());
            out.append(":[");
            boolean firstEntry = true;
            for (String entry : playlist.getValue()) {
                if (!firstEntry) out.append(',');
                firstEntry = false;
                StrictJson.quote(out, entry);
            }
            out.append(']');
        }
        return out.append('}').toString();
    }
}
