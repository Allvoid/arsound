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
    /** The transfer under way: what it reads and the playlists made so far, kept until it ends. */
    private static final String RUNNING = "running.json";
    /** Held for a whole read, change and write of a file, by the transfer and by the screen. */
    private static final Object LOCK = new Object();
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
        synchronized (LOCK) {
            write(MATCHES, matches.toString());
        }
    }

    /**
     * Marks a transfer as under way. A transfer whose app was closed in the middle stays marked, so the screen can say
     * so and offer to go on; the playlists it had made are reused then instead of made again.
     */
    static void markRunning(String key, String title, boolean like) {
        synchronized (LOCK) {
            JSONObject running = read(RUNNING);
            try {
                if (running == null) running = new JSONObject();
                // The same collection again keeps the playlists made by the try before.
                if (!key.equals(running.optString("key"))) switchRunning(running, key);
                running.put("title", title).put("like", like);
                write(RUNNING, running.toString());
            } catch (Exception ex) {
                Logger.printException(() -> "Transfer: could not mark the transfer", ex);
            }
        }
    }

    /**
     * Another collection goes first: the playlists of the one before wait in "left", under its key, so transferring that
     * collection again does not make them a second time.
     */
    private static void switchRunning(JSONObject running, String key) throws Exception {
        JSONObject left = running.optJSONObject("left");
        if (left == null) left = new JSONObject();
        JSONArray made = running.optJSONArray("playlists");
        String before = running.optString("key");
        if (!before.isEmpty() && made != null && made.length() > 0) left.put(before, made);
        JSONArray back = left.optJSONArray(key);
        left.remove(key);
        running.put("key", key).put("playlists", back == null ? new JSONArray() : back).put("left", left);
    }

    /** Remembers a playlist the transfer made, the moment it is made: a later failure must not lose it. */
    static void addMadePlaylist(String key, String urn) {
        synchronized (LOCK) {
            JSONObject running = read(RUNNING);
            if (running == null || !key.equals(running.optString("key"))) return;
            try {
                JSONArray playlists = running.optJSONArray("playlists");
                if (playlists == null) running.put("playlists", playlists = new JSONArray());
                playlists.put(urn);
                write(RUNNING, running.toString());
            } catch (Exception ex) {
                Logger.printException(() -> "Transfer: could not remember the playlist " + urn, ex);
            }
        }
    }

    /** The playlists a try of this collection made before it stopped, in their order. */
    static List<String> madePlaylists(String key) {
        List<String> made = new ArrayList<>();
        synchronized (LOCK) {
            JSONObject running = read(RUNNING);
            JSONArray playlists = running == null || !key.equals(running.optString("key")) ? null : running.optJSONArray("playlists");
            for (int i = 0; playlists != null && i < playlists.length(); i++) made.add(playlists.optString(i));
        }
        return made;
    }

    /** The transfer ended, finished or not: nothing more to go on with. What other collections left stays. */
    static void clearRunning() {
        synchronized (LOCK) {
            File folder = folder();
            if (folder == null) return;
            JSONObject running = read(RUNNING);
            JSONObject left = running == null ? null : running.optJSONObject("left");
            if (left == null || left.length() == 0) {
                new File(folder, RUNNING).delete();
                return;
            }
            try {
                write(RUNNING, new JSONObject().put("left", left).toString());
            } catch (Exception ex) {
                Logger.printException(() -> "Transfer: could not clear the transfer", ex);
            }
        }
    }

    /** Key, title and "true" or "false" for the likes of a transfer the app was closed in the middle of, or null. */
    static String[] interrupted() {
        synchronized (LOCK) {
            JSONObject running = read(RUNNING);
            if (running == null || running.optString("key").isEmpty()) return null;
            return new String[]{running.optString("key"), running.optString("title"),
                    String.valueOf(running.optBoolean("like"))};
        }
    }

    static List<TransferRecord> history() {
        synchronized (LOCK) {
            return historyLocked();
        }
    }

    private static List<TransferRecord> historyLocked() {
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
        synchronized (LOCK) {
            addToHistoryLocked(entry);
        }
    }

    private static void addToHistoryLocked(TransferRecord entry) {
        try {
            keepDamaged(HISTORY);
            List<TransferRecord> history = historyLocked();
            history.add(0, entry);
            JSONArray entries = new JSONArray();
            for (int i = 0; i < Math.min(HISTORY_SIZE, history.size()); i++) {
                TransferRecord kept = history.get(i);
                List<String> missing = kept.missing.size() > MISSING_KEPT ? kept.missing.subList(0, MISSING_KEPT) : kept.missing;
                entries.put(new TransferRecord(kept.key, kept.title, kept.time, kept.total, kept.found, kept.playlists, missing,
                        kept.liked).toJson());
            }
            write(HISTORY, new JSONObject().put("entries", entries).toString());
        } catch (Exception ex) {
            Logger.printException(() -> "Transfer: could not save the history", ex);
        }
    }

    static void removeFromHistory(long time) {
        synchronized (LOCK) {
            try {
                // A file that cannot be read is not written over with an empty list.
                if (keepDamaged(HISTORY)) return;
                JSONArray entries = new JSONArray();
                for (TransferRecord entry : historyLocked()) if (entry.time != time) entries.put(entry.toJson());
                write(HISTORY, new JSONObject().put("entries", entries).toString());
            } catch (Exception ex) {
                Logger.printException(() -> "Transfer: could not change the history", ex);
            }
        }
    }

    /**
     * Moves a file that is there but cannot be read aside, so a write does not replace what may still be saved by
     * hand. @return Whether it was damaged.
     */
    private static boolean keepDamaged(String name) {
        File folder = folder();
        File file = folder == null ? null : new File(folder, name);
        if (file == null || !file.isFile() || read(name) != null) return false;
        File aside = new File(folder, name + ".damaged");
        aside.delete();
        boolean moved = file.renameTo(aside);
        Logger.printInfo(() -> "Transfer: " + name + " could not be read, " + (moved ? "kept aside" : "left as it is"));
        return true;
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

    /**
     * Writes through a temporary file, so a crash in the middle leaves the old file whole. The callers hold
     * {@link #LOCK}; the temporary name is still the writer's own.
     */
    private static void write(String name, String text) {
        File folder = folder();
        if (folder == null) return;
        File part = new File(folder, name + "." + Thread.currentThread().getId() + ".part");
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
