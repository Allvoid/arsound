package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.UnplayedDownloads;
import app.revanced.extension.soundcloud.recommendations.ForYou;
import app.revanced.extension.soundcloud.recommendations.WaveMemory;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Listening statistics, kept only on this phone. The player reports the start of every track and
 * every switch between playing and not playing; the time a track really played is written to a
 * file ({@link HistoryFile}), one {@link ListenRecord} per listen: start time, track urn, milliseconds played.
 * <p>
 * A listen counts as a play from {@link #PLAY_THRESHOLD_MS} on, shorter ones only add time.
 * <p>
 * The listen is followed with the statistics turned off too: "My Wave" learns from it ({@link WaveMemory}). Then
 * nothing is written to the history file or kept for a restart.
 */
@SuppressWarnings("unused")
public final class ListeningStats {
    public static final String ENABLED = "listening_stats";
    public static final long PLAY_THRESHOLD_MS = 30_000;
    private static final String PREFERENCES_NAME = "arsound_listening_stats";
    /** The listen in progress, saved on every pause so it survives the app being closed. */
    private static final String PENDING = "pending";
    /** A listen shorter than this is not worth a line in the history. */
    private static final long MIN_LISTEN_MS = 1000;
    /** The ExoPlayer states: ready to play, and the end of the track. */
    private static final int STATE_READY = 3;
    private static final int STATE_ENDED = 4;

    /**
     * The listen in progress. Guarded by the class itself ({@code synchronized} methods): the player calls the hooks
     * below on its own thread and must not wait for a file to be read.
     */
    private static final ListenSession SESSION = new ListenSession();

    private ListeningStats() {
    }

    public static boolean isEnabled() {
        return Settings.getBoolean(ENABLED, true);
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    // region Recording

    /**
     * Injection point: {@code BaseExoPlayer.f(PlaybackItem)}, a queue item starts.
     *
     * @param item The {@code PlaybackItem}.
     */
    public static synchronized void onPlaybackItem(Object item) {
        try {
            Object urn = item.getClass().getMethod("h").invoke(item);
            String value = String.valueOf(urn);
            // Kept even with the statistics off: unplayed downloads are counted from it.
            UnplayedDownloads.onTrackStarted(value);
            // Before the resume check: a listen taken up again after a restart still needs its length. A preview item
            // gives the preview's length, so a preview heard through counts as heard.
            if (value.contains(":tracks:")) WaveMemory.onTrackStarted(value, itemLength(item));
            if (isResume(item, value)) return;
            finishListen();
            // Ads and other items that are not tracks are not counted.
            if (!value.contains(":tracks:")) return;
            // Followed with the statistics off too, for the wave: only the history is not kept then.
            SESSION.begin(value, System.currentTimeMillis());
        } catch (Exception ex) {
            Logger.printException(() -> "Listening stats: could not read the playback item", ex);
        }
    }

    /**
     * Play after a pause gives the player the same item again, starting where it stopped. That is the
     * same listen going on: counted as a new one, a track paused once became two short listens, and
     * often neither reached a play. Also continues the listen saved at a pause if the app was closed.
     *
     * @return True if the listen goes on.
     */
    private static boolean isResume(Object item, String urn) {
        long position;
        try {
            position = ((Number) item.getClass().getMethod("e").invoke(item)).longValue();
        } catch (Exception ex) {
            return false;
        }
        // From the start it is a new listen, also of the same track.
        if (position <= 0) return false;
        if (SESSION.isOf(urn)) return true;
        if (SESSION.isActive() || !isEnabled()) return false;
        SharedPreferences preferences = preferences();
        String pending = preferences == null ? null : preferences.getString(PENDING, null);
        String[] parts = pending == null ? new String[0] : pending.split("\t");
        if (parts.length != 3 || !parts[1].equals(urn)) return false;
        ListenRecord saved = ListenRecord.parse(pending);
        if (saved == null) {
            // A damaged mark of an earlier listen: forgotten, or it would be read again at every track.
            if (preferences != null) preferences.edit().remove(PENDING).apply();
            return false;
        }
        SESSION.resume(saved);
        Logger.printInfo(() -> "Listening stats: the listen saved at the pause goes on: " + urn);
        return true;
    }

    /**
     * The length SoundCloud's data gives for the track of the item ({@code PlaybackItem.a()}: the full length, or the
     * length of the preview for a preview item). The wave needs it to judge a short track.
     *
     * @return Milliseconds, 0 if the item does not say.
     */
    private static long itemLength(Object item) {
        try {
            return ((Number) item.getClass().getMethod("a").invoke(item)).longValue();
        } catch (Exception ex) {
            return 0;
        }
    }

    /**
     * Injection point: {@code onPlayerStateChanged} of the player listener.
     *
     * @param playWhenReady Whether the user wants the track to play.
     * @param state         The ExoPlayer state; 3 is ready.
     */
    public static synchronized void onPlayerState(boolean playWhenReady, int state) {
        if (!SESSION.isActive()) return;
        boolean playing = playWhenReady && state == STATE_READY;
        // The mark that survives a restart is a part of the history: not kept with the statistics off.
        if (SESSION.onPlayingChanged(playing, System.currentTimeMillis()) && isEnabled()) savePending();
        // The track ended by itself.
        if (state == STATE_ENDED) finishListen();
    }

    private static void finishListen() {
        if (!SESSION.isActive()) {
            flushPending();
            return;
        }
        ListenRecord ended = SESSION.end(System.currentTimeMillis());
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().remove(PENDING).apply();
        if (ended.playedMs >= MIN_LISTEN_MS && isEnabled()) append(ended);
        // The taste of the wave, with the statistics on or off: a track switched off in the first seconds, or heard
        // to a good length (the length of the track was noted when it started).
        WaveMemory.onListen(ended.urn, ended.playedMs);
    }

    private static void savePending() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        preferences.edit().putString(PENDING, SESSION.pendingMark().format()).apply();
    }

    /** A listen the app was closed in the middle of. */
    private static void flushPending() {
        SharedPreferences preferences = preferences();
        String pending = preferences == null ? null : preferences.getString(PENDING, null);
        if (pending == null) return;
        preferences.edit().remove(PENDING).apply();
        // A mark left from before the statistics were turned off is dropped, not written.
        if (!isEnabled()) return;
        ListenRecord saved = ListenRecord.parse(pending);
        if (saved != null) append(saved);
    }

    private static void append(ListenRecord record) {
        Logger.printInfo(() -> "Listening stats: " + record.urn + " played " + record.playedMs / 1000 + " s");
        HistoryFile.append(record);
    }

    public static synchronized void clear() {
        HistoryFile.delete();
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().remove(PENDING).apply();
    }

    // endregion

    // region Reading

    /** A track or an artist in the statistics, with what was played of it. */
    public static final class Entry {
        public final String key;
        public String title;
        public String artist;
        public int plays;
        public long playedMs;

        Entry(String key) {
            this.key = key;
        }

        /** Adds a listen: the time always, a play only if it was long enough. */
        void addListen(long played) {
            playedMs += played;
            if (played >= PLAY_THRESHOLD_MS) plays++;
        }
    }

    public static final class Summary {
        public long playedMs;
        public int plays;
        public int tracks;
        public final List<Entry> topTracks = new ArrayList<>();
        public final List<Entry> topArtists = new ArrayList<>();

        /** Adds a listen of the track to the totals and to its entry. */
        void addListen(Entry track, long played) {
            track.addListen(played);
            playedMs += played;
            if (played >= PLAY_THRESHOLD_MS) plays++;
        }
    }

    /** The start of the last listen of each track in the statistics, by its urn. */
    public static Map<String, Long> lastListens() {
        Map<String, Long> result = new HashMap<>();
        HistoryFile.forEachLine(line -> {
            // One damaged line must not hide the rest of the history: the tracks after it would count as unplayed.
            ListenRecord record = ListenRecord.parseStartAndUrn(line);
            if (record == null) return;
            Long known = result.get(record.urn);
            if (known == null || known < record.start) result.put(record.urn, record.start);
        });
        return result;
    }

    /**
     * Sums up the listens since the given time. Blocks: may ask SoundCloud for track titles.
     *
     * @param since Epoch milliseconds, 0 for all time.
     */
    public static Summary summarize(long since, int limit) {
        Summary summary = new Summary();
        Map<String, Entry> tracks = new HashMap<>();
        HistoryFile.forEachLine(line -> {
            // One damaged line must not hide the rest of the history.
            ListenRecord record = ListenRecord.parse(line);
            if (record == null || record.start < since) return;
            summary.addListen(entry(tracks, record.urn), record.playedMs);
        });
        addCurrent(summary, tracks, since);
        summary.tracks = tracks.size();

        List<Entry> sorted = new ArrayList<>(tracks.values());
        sorted.sort((a, b) -> a.plays != b.plays ? Integer.compare(b.plays, a.plays) : Long.compare(b.playedMs, a.playedMs));
        // Artists need the titles of all tracks, not only the top ones.
        TrackTitleResolver.resolve(sorted, preferences());
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) summary.topTracks.add(sorted.get(i));

        List<Entry> artistList = groupByArtist(sorted);
        artistList.sort((a, b) -> Long.compare(b.playedMs, a.playedMs));
        for (int i = 0; i < Math.min(limit, artistList.size()); i++) summary.topArtists.add(artistList.get(i));
        return summary;
    }

    private static Entry entry(Map<String, Entry> entries, String key) {
        Entry entry = entries.get(key);
        if (entry == null) entries.put(key, entry = new Entry(key));
        return entry;
    }

    /**
     * One artist is written in different ways ("CUPSIZE", "Cupsize", "Cupsize & ..."): counted together and named
     * as written on the tracks played the longest.
     */
    private static List<Entry> groupByArtist(List<Entry> tracks) {
        Map<String, Entry> artists = new HashMap<>();
        Map<String, Map<String, Long>> spellings = new HashMap<>();
        for (Entry track : tracks) {
            if (track.artist == null || track.artist.trim().isEmpty()) continue;
            String key = ForYou.artistKey(track.artist);
            if (key.isEmpty()) key = track.artist.trim().toLowerCase(Locale.ROOT);
            Entry artist = entry(artists, key);
            artist.plays += track.plays;
            artist.playedMs += track.playedMs;
            Map<String, Long> names = spellings.get(key);
            if (names == null) spellings.put(key, names = new HashMap<>());
            names.merge(track.artist.trim(), track.playedMs, Long::sum);
        }
        for (Entry artist : artists.values()) {
            long longest = -1;
            for (Map.Entry<String, Long> name : spellings.get(artist.key).entrySet()) {
                if (name.getValue() > longest) {
                    longest = name.getValue();
                    artist.title = name.getKey();
                }
            }
        }
        return new ArrayList<>(artists.values());
    }

    /** The listen still going on or paused: it is written only when the next track starts. */
    private static void addCurrent(Summary summary, Map<String, Entry> tracks, long since) {
        // With the statistics off the listen is followed only for the wave and never written: it is no part of them.
        if (!isEnabled()) return;
        ListenRecord current;
        synchronized (ListeningStats.class) {
            current = SESSION.progress(System.currentTimeMillis());
        }
        if (current == null) {
            SharedPreferences preferences = preferences();
            String pending = preferences == null ? null : preferences.getString(PENDING, null);
            current = pending == null ? null : ListenRecord.parse(pending);
            if (current == null) return;
        }
        if (current.start < since || current.playedMs < MIN_LISTEN_MS) return;
        summary.addListen(entry(tracks, current.urn), current.playedMs);
    }

    // endregion
}
