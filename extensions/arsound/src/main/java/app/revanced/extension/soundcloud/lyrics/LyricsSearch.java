package app.revanced.extension.soundcloud.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.lyrics.transcribe.TranscriptionManager;
import app.revanced.extension.soundcloud.shared.SearchText;

/**
 * The search of "Your likes" also finds a track by a line of its lyrics: the lyrics already on the phone (found online
 * and kept, or recognised by the neural network), nothing goes to the network. A track found by its words gets the mark
 * "в тексте" under its title in the results; no new screen and no new button.
 * <p>
 * The texts are read once, in the background, at the first search, and every text saved later joins at once. They are
 * kept in memory in the search's own form (lower case, no punctuation), urn to text.
 */
public final class LyricsSearch {
    /** Shorter queries would find half the library. */
    static final int MIN_QUERY = 3;
    /** The most tracks one query adds to the results. */
    static final int MAX_MATCHES = 300;
    /** How long the marks of the last search stay: the search screen asks again as the user types. */
    private static final long MARK_MS = 10 * 60 * 1000;
    private static final Pattern WHERE_END = Pattern.compile("\\)\\s*AND\\s+likes\\.removedAt\\s+IS\\s+NULL");
    private static final Pattern TRACK_URN = Pattern.compile("soundcloud:tracks:\\d+");
    private static final Pattern SAFE_TRACK = Pattern.compile("soundcloud_tracks_(\\d+)");

    /** The texts are read again after this: files the stores pruned are forgotten. */
    private static final long RELOAD_MS = 30 * 60 * 1000;

    private static volatile Map<String, String> texts = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicBoolean LOADING = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long loadedAt;
    private static volatile Set<String> lastMatches = Collections.emptySet();
    private static volatile String lastWords = "";
    private static volatile long lastAt;

    private LyricsSearch() {
    }

    /** A text was saved: it can be found from now on. Any thread. */
    public static void onSaved(String urn, String[] lines) {
        if (urn == null || lines == null) return;
        texts.put(urn, SearchText.normalize(String.join(" ", lines)));
    }

    /** The stored texts were deleted (the user cleared them): they are read again at the next search. */
    public static void invalidate() {
        texts = new ConcurrentHashMap<>();
        loadedAt = 0;
        clearMarks();
    }

    /** The urn of an older cache file, whose name is the urn made safe: only track urns can be read back. */
    public static String urnOfSafeName(String name) {
        Matcher matcher = SAFE_TRACK.matcher(name);
        return matcher.matches() ? "soundcloud:tracks:" + matcher.group(1) : null;
    }

    /**
     * Reads the stored texts in the background, never on the thread that asks: the first search must not hold SoundCloud's
     * database while hundreds of files are read. Read again every {@link #RELOAD_MS}, so pruned files drop out.
     */
    static void loadSoon() {
        if (loadedAt != 0 && System.currentTimeMillis() - loadedAt < RELOAD_MS) return;
        if (!LOADING.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> {
            long start = System.currentTimeMillis();
            Map<String, String> fresh = new ConcurrentHashMap<>();
            try {
                LyricsRepository.forEachCached((urn, lines) -> fresh.put(urn, SearchText.normalize(String.join(" ", lines))));
            } catch (Throwable ex) {
                Logger.printException(() -> "Lyrics search: could not read the stored lyrics", ex);
            }
            try {
                TranscriptionManager.forEachTranscript((urn, lines) ->
                        fresh.putIfAbsent(urn, SearchText.normalize(String.join(" ", lines))));
            } catch (Throwable ex) {
                Logger.printException(() -> "Lyrics search: could not read the recognised lyrics", ex);
            }
            // Texts saved while this ran stay.
            fresh.putAll(texts);
            texts = fresh;
            loadedAt = System.currentTimeMillis();
            LOADING.set(false);
            long took = loadedAt - start;
            Logger.printInfo(() -> "Lyrics search: " + fresh.size() + " texts read in " + took + " ms");
        }, "arsound-lyrics-search");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** The tracks whose lyrics hold every word of the query, at most {@link #MAX_MATCHES}. */
    static List<String> matching(String query, Map<String, String> texts) {
        String[] words = SearchText.words(query);
        List<String> result = new ArrayList<>();
        if (SearchText.normalize(query).length() < MIN_QUERY) return result;
        for (Map.Entry<String, String> entry : texts.entrySet()) {
            if (SearchText.containsAll(entry.getValue(), words)) {
                result.add(entry.getKey());
                if (result.size() >= MAX_MATCHES) break;
            }
        }
        return result;
    }

    /**
     * Injection point: {@code RoomLikesReadStorage.getLikedTracks}, the SQL of the likes search, built. The liked tracks
     * whose lyrics hold the query join the ones found by title and artist. Runs on SoundCloud's database thread.
     *
     * @return The SQL with the lyrics matches, or as it was.
     */
    public static String widen(String sql, String query) {
        try {
            if (sql == null || query == null || SearchText.normalize(query).length() < MIN_QUERY) {
                // The plain list of likes, or a query too short: no row is marked. The texts are read now, ahead of a search.
                clearMarks();
                loadSoon();
                return sql;
            }
            // The texts read so far: the first search right after the start may not have them all yet.
            loadSoon();
            Set<String> urns = new LinkedHashSet<>();
            for (String urn : matching(query, texts)) if (TRACK_URN.matcher(urn).matches()) urns.add(urn);
            lastMatches = urns;
            lastWords = SearchText.normalize(query);
            lastAt = System.currentTimeMillis();
            if (urns.isEmpty()) return sql;
            String widened = widenSql(sql, urns);
            if (widened.equals(sql)) Logger.printInfo(() -> "Lyrics search: the likes query has another shape, left as it is");
            else Logger.printInfo(() -> "Lyrics search: \"" + query + "\" found in the lyrics of " + urns.size() + " tracks");
            return widened;
        } catch (Throwable ex) {
            Logger.printException(() -> "Lyrics search: could not widen the likes search", ex);
            return sql;
        }
    }

    /** The WHERE of the likes search with "or the track is one of these" before its closing bracket. */
    static String widenSql(String sql, Set<String> urns) {
        Matcher matcher = WHERE_END.matcher(sql);
        if (!matcher.find() || urns.isEmpty()) return sql;
        StringBuilder list = new StringBuilder();
        for (String urn : urns) {
            // Only "soundcloud:tracks:<digits>" reaches here: nothing to escape, checked again to be sure.
            if (!TRACK_URN.matcher(urn).matches()) continue;
            if (list.length() > 0) list.append(',');
            list.append('\'').append(urn).append('\'');
        }
        if (list.length() == 0) return sql;
        return sql.substring(0, matcher.start()) + " OR Tracks.urn IN (" + list + ") " + sql.substring(matcher.start());
    }

    /**
     * The mark of a row of the search results that was found by its lyrics, not by its title or artist; null for every
     * other row.
     */
    public static String markFor(String urn, String title, String artist) {
        if (urn == null || !lastMatches.contains(urn) || System.currentTimeMillis() - lastAt > MARK_MS) return null;
        String words = lastWords;
        String[] parts = words.isEmpty() ? new String[0] : words.split(" ");
        if (SearchText.containsAll(SearchText.normalize(title + " " + artist), parts)) return null;
        return app.revanced.extension.soundcloud.shared.Lang.text("в тексте", "in lyrics");
    }

    /** The search was left: the marks go. */
    public static void clearMarks() {
        lastMatches = Collections.emptySet();
    }
}
