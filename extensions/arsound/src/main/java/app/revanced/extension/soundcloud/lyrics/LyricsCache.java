package app.revanced.extension.soundcloud.lyrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Answers of the lyrics search kept on the phone, one small file per track named after its URN, so a track that was
 * looked up once opens at once and without a connection.
 * <p>
 * A file belongs to the track as it was named and as long as it was ("signature"): a track renamed or uploaded again
 * is looked up again. Texts are kept for a year, "no lyrics" for a week, in case someone adds the song to LRCLIB.
 * Failures are never stored.
 */
final class LyricsCache {
    private static final int VERSION = 1;
    private static final long FOUND_TTL_MS = 365L * 24 * 3600 * 1000;
    private static final long MISSING_TTL_MS = 7L * 24 * 3600 * 1000;
    private static final int MAX_FILES = 1000;
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    private final File directory;
    private int writesSincePrune;

    LyricsCache(File directory) {
        this.directory = directory;
    }

    /** @return The stored answer, or null if there is none, it is out of date or it is for another version of the track. */
    synchronized LyricsResult read(String urn, String signature) {
        File file = file(urn);
        if (!file.isFile()) return null;
        try {
            JSONObject json = new JSONObject(new String(readAll(file), StandardCharsets.UTF_8));
            if (json.optInt("v") != VERSION || !signature.equals(json.optString("sig"))) return null;
            long age = System.currentTimeMillis() - json.optLong("at");
            String status = json.optString("status");
            if ("missing".equals(status)) return age <= MISSING_TTL_MS && age >= 0 ? LyricsResult.missing() : null;
            if (age > FOUND_TTL_MS || age < 0) return null;
            if ("instrumental".equals(status)) return LyricsResult.instrumental();
            if (!"found".equals(status)) return null;
            JSONArray lines = json.getJSONArray("lines");
            String[] text = new String[lines.length()];
            for (int i = 0; i < text.length; i++) text[i] = lines.getString(i);
            JSONArray stamps = json.optJSONArray("times");
            long[] times = null;
            if (stamps != null && stamps.length() == text.length) {
                times = new long[text.length];
                for (int i = 0; i < times.length; i++) times[i] = stamps.getLong(i);
            }
            return text.length == 0 ? null : LyricsResult.found(new Lyrics(text, times));
        } catch (IOException | JSONException ex) {
            // A broken file is no answer; the next lookup replaces it.
            return null;
        }
    }

    synchronized void write(String urn, String signature, LyricsResult result) {
        if (!result.isStorable()) return;
        try {
            JSONObject json = new JSONObject();
            json.put("v", VERSION);
            json.put("sig", signature);
            json.put("at", System.currentTimeMillis());
            switch (result.status) {
                case FOUND:
                    json.put("status", "found");
                    json.put("lines", new JSONArray(Arrays.asList(result.lyrics.lines)));
                    if (result.lyrics.times != null) {
                        JSONArray stamps = new JSONArray();
                        for (long time : result.lyrics.times) stamps.put(time);
                        json.put("times", stamps);
                    }
                    break;
                case INSTRUMENTAL:
                    json.put("status", "instrumental");
                    break;
                default:
                    json.put("status", "missing");
                    break;
            }
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            File file = file(urn);
            File temporary = new File(directory, file.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            // Written whole or not at all: a track opened right now never reads half a file.
            if (!temporary.renameTo(file)) {
                if (file.exists() && !file.delete()) return;
                if (!temporary.renameTo(file)) temporary.delete();
            }
            if (++writesSincePrune >= 25) {
                writesSincePrune = 0;
                prune();
            }
        } catch (IOException | JSONException ex) {
            // Not stored: the next play looks the track up again.
        }
    }

    /** Space the stored answers take, in bytes. */
    synchronized long size() {
        long total = 0;
        File[] files = directory.listFiles();
        if (files == null) return 0;
        for (File file : files) total += file.length();
        return total;
    }

    synchronized void clear() {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) file.delete();
    }

    /** Keeps the newest answers within the limits. */
    private void prune() {
        File[] files = directory.listFiles();
        if (files == null) return;
        long total = 0;
        for (File file : files) total += file.length();
        if (files.length <= MAX_FILES && total <= MAX_BYTES) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.lastModified() < b.lastModified() ? -1 : a.lastModified() == b.lastModified() ? 0 : 1;
            }
        });
        int count = files.length;
        for (File file : files) {
            if (count <= MAX_FILES * 9 / 10 && total <= MAX_BYTES * 9 / 10) break;
            total -= file.length();
            count--;
            file.delete();
        }
    }

    private File file(String urn) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < urn.length(); i++) {
            char c = urn.charAt(i);
            name.append(Character.isLetterOrDigit(c) && c < 128 || c == '-' || c == '_' ? c : '_');
        }
        return new File(directory, name + ".json");
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
