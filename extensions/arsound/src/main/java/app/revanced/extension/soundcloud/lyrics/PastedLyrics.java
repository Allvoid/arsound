package app.revanced.extension.soundcloud.lyrics;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.lyrics.transcribe.Transcript;
import app.revanced.extension.soundcloud.lyrics.transcribe.TranscriptionManager;
import app.revanced.extension.soundcloud.shared.AtomicFiles;

/**
 * The words of a song that the user pasted, one small file per track in the app's files (they are the user's own, so
 * not in the cache that the system may clear). The text is kept as it was pasted, so it can be edited, next to the
 * lines and times made from it by {@link LyricsAligner}; a pasted text always wins over what the search finds.
 * <p>
 * The file reads and writes take the folder, so they run in the JVM tests; {@link #lookUp}, {@link #save} and
 * {@link #remove} are the ones the player uses, and block: not on the main thread.
 */
final class PastedLyrics {
    private static final String FOLDER = "arsound-lyrics-pasted";
    /** A pasted text longer than this is refused: no song has so many words. */
    static final int MAX_CHARS = 20_000;

    private PastedLyrics() {
    }

    // region For the player

    /** The pasted words of the track, or null when there are none. Reads a file. */
    static Lyrics lookUp(TrackInfo info) {
        File folder = folder();
        return folder == null ? null : read(folder, info.urn);
    }

    /** The text as the user pasted it, for editing; null when there is none. */
    static String rawText(TrackInfo info) {
        File folder = folder();
        return folder == null ? null : readRaw(folder, info.urn);
    }

    /**
     * Aligns the text with the track and keeps it.
     *
     * @return The words to show, or null when the text is empty or could not be kept.
     */
    static Lyrics save(TrackInfo info, String pasted) {
        File folder = folder();
        if (folder == null || pasted == null) return null;
        String text = pasted.length() > MAX_CHARS ? pasted.substring(0, MAX_CHARS) : pasted;
        Transcript heard = null;
        try {
            heard = TranscriptionManager.stored(info.urn, info.durationMs);
        } catch (Throwable ex) {
            Logger.printInfo(() -> "Lyrics: no recognised text to align the pasted one with: " + ex);
        }
        LyricsAligner.Result result = LyricsAligner.align(text, heard == null ? null : heard.lines,
                heard == null ? null : heard.times, info.durationMs);
        if (result.lines.length == 0) return null;
        Lyrics lyrics = toLyrics(result);
        try {
            write(folder, info.urn, text, lyrics, info.durationMs);
        } catch (IOException | RuntimeException ex) {
            Logger.printException(() -> "Lyrics: could not keep the pasted text", ex);
            return null;
        }
        return lyrics;
    }

    /** Forgets the pasted words of the track. */
    static void remove(TrackInfo info) {
        File folder = folder();
        if (folder != null) delete(folder, info.urn);
    }

    private static File folder() {
        Context context = Utils.getContext();
        return context == null ? null : new File(context.getFilesDir(), FOLDER);
    }

    // endregion

    static Lyrics toLyrics(LyricsAligner.Result result) {
        Lyrics.Source source = result.mode == LyricsAligner.Mode.APPROXIMATE ? Lyrics.Source.PASTED_APPROX
                : Lyrics.Source.PASTED;
        return new Lyrics(result.lines, result.times, source);
    }

    // region Files

    /** The name of the file of a track: every character that a file name may not have is replaced. */
    static File fileOf(File folder, String urn) {
        return new File(folder, urn.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
    }

    static void write(File folder, String urn, String raw, Lyrics lyrics, long durationMs) throws IOException {
        try {
            JSONObject json = new JSONObject();
            json.put("version", 1);
            json.put("raw", raw);
            json.put("approximate", lyrics.source == Lyrics.Source.PASTED_APPROX);
            json.put("duration", durationMs);
            JSONArray lines = new JSONArray();
            for (String line : lyrics.lines) lines.put(line);
            json.put("lines", lines);
            if (lyrics.times != null) {
                JSONArray times = new JSONArray();
                for (long time : lyrics.times) times.put(time);
                json.put("times", times);
            }
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("No folder " + folder);
            File target = fileOf(folder, urn);
            AtomicFiles.write(new File(target.getPath() + ".part"), target, json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (org.json.JSONException ex) {
            throw new IOException(ex);
        }
    }

    static Lyrics read(File folder, String urn) {
        JSONObject json = readJson(folder, urn);
        if (json == null) return null;
        try {
            JSONArray lines = json.getJSONArray("lines");
            if (lines.length() == 0) return null;
            String[] words = new String[lines.length()];
            for (int i = 0; i < words.length; i++) words[i] = lines.getString(i);
            long[] times = null;
            JSONArray kept = json.optJSONArray("times");
            if (kept != null) {
                if (kept.length() != words.length) return null;
                times = new long[words.length];
                for (int i = 0; i < times.length; i++) times[i] = kept.getLong(i);
            }
            return new Lyrics(words, times, json.optBoolean("approximate") ? Lyrics.Source.PASTED_APPROX : Lyrics.Source.PASTED);
        } catch (org.json.JSONException ex) {
            return null;
        }
    }

    static String readRaw(File folder, String urn) {
        JSONObject json = readJson(folder, urn);
        return json == null ? null : json.optString("raw", null);
    }

    static void delete(File folder, String urn) {
        File file = fileOf(folder, urn);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static JSONObject readJson(File folder, String urn) {
        File file = fileOf(folder, urn);
        if (!file.isFile()) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            for (int read; (read = input.read(buffer)) > 0; ) out.write(buffer, 0, read);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException | org.json.JSONException ex) {
            return null;
        }
    }

    // endregion
}
