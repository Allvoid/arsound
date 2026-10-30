package app.revanced.extension.soundcloud.transfer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.network.StreamText;

/**
 * What the transfers keep on the phone, as JSON in {@code files/arsound-transfer}: the SoundCloud match of every
 * Yandex track looked up (so a stopped or repeated transfer does not search again) and the transfers done, with the
 * tracks not found.
 */
final class TransferStore {
    private static final String FOLDER = "arsound-transfer";
    private static final String MATCHES = "matches.json";
    private static final String HISTORY = "history.json";
    private static final long FOUND_KEPT_MS = TimeUnit.DAYS.toMillis(60);
    /** A track not found is looked up again sooner: it may be uploaded since. */
    private static final long MISSING_KEPT_MS = TimeUnit.DAYS.toMillis(3);
    private static final int HISTORY_SIZE = 20;
    private static final int MISSING_KEPT = 3000;

    private TransferStore() {
    }

    private static File folder() {
        Context context = Utils.getContext();
        if (context == null) return null;
        File folder = new File(context.getFilesDir(), FOLDER);
        return folder.isDirectory() || folder.mkdirs() ? folder : null;
    }

    /** The kept matches: Yandex track id to {"urn": "soundcloud:tracks:…" or "" for not found, "time": …}. */
    static JSONObject loadMatches() {
        JSONObject all = read(MATCHES);
        if (all == null) return new JSONObject();
        long now = System.currentTimeMillis();
        List<String> old = new ArrayList<>();
        for (java.util.Iterator<String> keys = all.keys(); keys.hasNext(); ) {
            String key = keys.next();
            JSONObject match = all.optJSONObject(key);
            long kept = match == null || match.optString("urn").isEmpty() ? MISSING_KEPT_MS : FOUND_KEPT_MS;
            if (match == null || now - match.optLong("time") > kept) old.add(key);
        }
        for (String key : old) all.remove(key);
        return all;
    }

    static void saveMatches(JSONObject matches) {
        write(MATCHES, matches.toString());
    }

    static List<TransferRecord> history() {
        JSONObject stored = read(HISTORY);
        JSONArray entries = stored == null ? null : stored.optJSONArray("entries");
        List<TransferRecord> history = new ArrayList<>();
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null) history.add(TransferRecord.fromJson(entry));
        }
        return history;
    }

    /** The newest first; the same collection transferred again replaces nothing, both stay. */
    static void addToHistory(TransferRecord entry) {
        try {
            List<TransferRecord> history = history();
            history.add(0, entry);
            JSONArray entries = new JSONArray();
            for (int i = 0; i < Math.min(HISTORY_SIZE, history.size()); i++) {
                TransferRecord kept = history.get(i);
                List<String> missing = kept.missing.size() > MISSING_KEPT ? kept.missing.subList(0, MISSING_KEPT) : kept.missing;
                entries.put(new TransferRecord(kept.key, kept.title, kept.time, kept.total, kept.found, kept.playlists, missing).toJson());
            }
            write(HISTORY, new JSONObject().put("entries", entries).toString());
        } catch (Exception ex) {
            Logger.printException(() -> "Transfer: could not save the history", ex);
        }
    }

    static void removeFromHistory(long time) {
        try {
            JSONArray entries = new JSONArray();
            for (TransferRecord entry : history()) if (entry.time != time) entries.put(entry.toJson());
            write(HISTORY, new JSONObject().put("entries", entries).toString());
        } catch (Exception ex) {
            Logger.printException(() -> "Transfer: could not change the history", ex);
        }
    }

    private static JSONObject read(String name) {
        File folder = folder();
        File file = folder == null ? null : new File(folder, name);
        if (file == null || !file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            return new JSONObject(StreamText.readUtf8(in));
        } catch (Exception ex) {
            // A damaged file is an empty one.
            Logger.printInfo(() -> "Transfer: could not read " + name + ": " + ex);
            return null;
        }
    }

    /** Writes through a temporary file, so a crash in the middle leaves the old file whole. */
    private static void write(String name, String text) {
        File folder = folder();
        if (folder == null) return;
        File part = new File(folder, name + ".part");
        try {
            try (OutputStream out = new FileOutputStream(part)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!part.renameTo(new File(folder, name))) throw new IOException("Could not rename " + part);
        } catch (IOException ex) {
            Logger.printException(() -> "Transfer: could not save " + name, ex);
        }
    }
}
