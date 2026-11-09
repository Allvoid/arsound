package app.revanced.extension.soundcloud.recommendations;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The "By artists" mode of "My Wave": instead of the person's taste (the likes and what was heard), the wave grows from the tracks
 * of a few artists they picked. The wave is then as narrow as those artists, and wider than the radio of one of them: the sources
 * (SoundCloud's related tracks, Last.fm, Deezer) are asked about the artists' own tracks, and some of those tracks play too. One
 * tap on "By taste" goes back to the usual mix; the artists stay picked for the next time.
 * <p>
 * The artists are SoundCloud accounts (who uploaded the tracks), kept in the settings with their names. Their tracks are asked
 * from SoundCloud once in a while and kept in memory.
 */
public final class WaveArtists {
    static final int MAX = 10;
    private static final String MODE = "wave_mode";
    private static final String ARTISTS = "wave_artists";
    private static final String MODE_ARTISTS = "artists";
    /** Tracks of one artist asked at once, and how long they are kept. */
    private static final int TRACKS_PER_ARTIST = 30;
    private static final long FRESH_MS = 30 * 60 * 1000L;
    private static final String TRACKS = "soundcloud:tracks:";
    /** How long the artists' tracks are waited for at once (they are asked together). */
    private static final long READ_WAIT_MS = 12_000;
    /** An artist whose tracks could not be read is not asked again before this. */
    private static final long FAILED_REST_MS = 2 * 60 * 1000L;

    /** An artist: a SoundCloud account. */
    public static final class Artist {
        public final long id;
        public final String name;
        /** How many follow the account, to tell apart accounts of the same name; 0 when not known (it is not kept). */
        public final long followers;

        public Artist(long id, String name) {
            this(id, name, 0);
        }

        Artist(long id, String name, long followers) {
            this.id = id;
            this.name = name == null ? "" : name.trim();
            this.followers = Math.max(0, followers);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Artist && ((Artist) other).id == id;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(id);
        }
    }

    private static final Map<Long, List<String>> TRACKS_OF = new HashMap<>();
    private static final Map<Long, Long> READ_AT = new HashMap<>();
    private static final Map<Long, Long> FAILED_AT = new HashMap<>();

    private WaveArtists() {
    }

    /** The artists picked, in the order they were picked. */
    public static List<Artist> chosen() {
        return parse(Settings.getString(ARTISTS, "[]"));
    }

    public static void save(List<Artist> artists) {
        Settings.putString(ARTISTS, format(artists.subList(0, Math.min(artists.size(), MAX))));
    }

    /** Whether the wave grows from the artists: the mode is on and there is someone to grow from. */
    public static boolean isOn() {
        return MODE_ARTISTS.equals(Settings.getString(MODE, "")) && !chosen().isEmpty();
    }

    public static void setOn(boolean on) {
        Settings.putString(MODE, on ? MODE_ARTISTS : "taste");
    }

    /** "A, B, C" for the line under the switch. */
    public static String names(List<Artist> artists) {
        StringBuilder out = new StringBuilder();
        for (Artist artist : artists) {
            if (out.length() > 0) out.append(", ");
            out.append(artist.name);
        }
        return out.toString();
    }

    /** "12K", "1.2M": the followers in a few characters. */
    static String compact(long count) {
        if (count >= 1_000_000) return trimZero(count / 100_000 / 10.0) + "M";
        if (count >= 1_000) return trimZero(count / 100 / 10.0) + "K";
        return String.valueOf(count);
    }

    private static String trimZero(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    static List<Artist> parse(String text) {
        List<Artist> out = new ArrayList<>();
        try {
            JSONArray items = new JSONArray(text == null ? "[]" : text);
            for (int i = 0; i < items.length() && out.size() < MAX; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || item.optLong("id", 0) <= 0) continue;
                Artist artist = new Artist(item.optLong("id"), item.optString("name"));
                if (!out.contains(artist)) out.add(artist);
            }
        } catch (Exception ex) {
            // A damaged record: the artists read before the damage stay. (No log here: the JVM tests run this without Android.)
        }
        return out;
    }

    static String format(List<Artist> artists) {
        JSONArray items = new JSONArray();
        try {
            for (Artist artist : artists) items.put(new JSONObject().put("id", artist.id).put("name", artist.name));
        } catch (Exception ex) {
            Logger.printInfo(() -> "My Wave: the artists could not be kept: " + ex);
        }
        return items.toString();
    }

    /**
     * The tracks of the artists picked, a few of each in turn (the first of every artist, then the second...), so no artist
     * fills the seeds alone. Blocking: off the main thread.
     */
    static List<String> tracks() {
        List<Artist> artists = chosen();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, Math.min(4, artists.size())));
        try {
            List<java.util.concurrent.Future<List<String>>> asked = new ArrayList<>();
            for (Artist artist : artists) asked.add(pool.submit(() -> tracksOf(artist.id)));
            long until = System.currentTimeMillis() + READ_WAIT_MS;
            List<List<String>> each = new ArrayList<>();
            for (int i = 0; i < asked.size(); i++) {
                try {
                    each.add(asked.get(i).get(Math.max(0, until - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS));
                } catch (Exception ex) {
                    each.add(kept(artists.get(i).id));
                }
            }
            return interleave(each);
        } finally {
            pool.shutdownNow();
        }
    }

    /** The tracks read before for an artist, however old; empty when there are none. */
    private static List<String> kept(long id) {
        synchronized (TRACKS_OF) {
            List<String> kept = TRACKS_OF.get(id);
            return kept == null ? new ArrayList<>() : kept;
        }
    }

    static List<String> interleave(List<List<String>> lists) {
        List<String> out = new ArrayList<>();
        for (int round = 0; ; round++) {
            boolean any = false;
            for (List<String> list : lists) {
                if (round >= list.size()) continue;
                any = true;
                String urn = list.get(round);
                if (!out.contains(urn)) out.add(urn);
            }
            if (!any) return out;
        }
    }

    private static List<String> tracksOf(long id) {
        synchronized (TRACKS_OF) {
            Long readAt = READ_AT.get(id);
            List<String> kept = TRACKS_OF.get(id);
            long age = readAt == null ? Long.MAX_VALUE : System.currentTimeMillis() - readAt;
            if (kept != null && age < FRESH_MS) return kept;
            Long failedAt = FAILED_AT.get(id);
            if (failedAt != null && System.currentTimeMillis() - failedAt < FAILED_REST_MS) return kept == null ? new ArrayList<>() : kept;
        }
        // The most played first; an artist with no plays counted yet gives their newest instead.
        List<String> found = read("https://api-v2.soundcloud.com/users/" + id + "/toptracks?limit=" + TRACKS_PER_ARTIST, id);
        if (found.isEmpty()) found = read("https://api-v2.soundcloud.com/users/" + id + "/tracks?limit=" + TRACKS_PER_ARTIST, id);
        synchronized (TRACKS_OF) {
            if (found.isEmpty()) {
                // No answer (no network, an account gone): the list read before stays in use, and the artist rests a while.
                FAILED_AT.put(id, System.currentTimeMillis());
                List<String> kept = TRACKS_OF.get(id);
                return kept == null ? new ArrayList<>() : kept;
            }
            FAILED_AT.remove(id);
            TRACKS_OF.put(id, found);
            READ_AT.put(id, System.currentTimeMillis());
        }
        return found;
    }

    private static List<String> read(String url, long id) {
        try {
            String[] answer = DownloadTrackPatch.apiGet(url);
            return answer[1] == null ? new ArrayList<>() : parseTracks(answer[1]);
        } catch (Exception ex) {
            Logger.printInfo(() -> "My Wave: the tracks of artist " + id + " could not be read: " + ex);
            return new ArrayList<>();
        }
    }

    /** The playable tracks of a "collection" answer of api-v2 (a snippet or a blocked track is left out). */
    static List<String> parseTracks(String body) throws Exception {
        List<String> urns = new ArrayList<>();
        JSONArray items = new JSONObject(body).optJSONArray("collection");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject track = items.optJSONObject(i);
            if (track == null) continue;
            if (track.has("track")) track = track.optJSONObject("track");
            if (track == null || track.optLong("id", 0) == 0) continue;
            String policy = track.optString("policy", "");
            if ("BLOCK".equals(policy) || "SNIP".equals(policy)) continue;
            urns.add(TRACKS + track.getLong("id"));
        }
        return urns;
    }

    /** The accounts of a "collection" of users (a search), or of the uploaders of a list of tracks (likes), each once. */
    static List<Artist> parseArtists(String body) throws Exception {
        Map<Long, Artist> out = new LinkedHashMap<>();
        JSONArray items = new JSONObject(body).optJSONArray("collection");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            JSONObject user = item;
            if (item.has("track")) {
                JSONObject track = item.optJSONObject("track");
                user = track == null ? null : track.optJSONObject("user");
            } else if (item.has("user") && !"user".equals(item.optString("kind"))) {
                user = item.optJSONObject("user");
            }
            if (user == null || user.optLong("id", 0) <= 0) continue;
            String name = user.optString("username", "").trim();
            if (name.isEmpty()) continue;
            out.putIfAbsent(user.getLong("id"), new Artist(user.getLong("id"), name, user.optLong("followers_count", 0)));
        }
        return new ArrayList<>(out.values());
    }

    /** People found on SoundCloud by name. Blocking. */
    static List<Artist> search(String query) {
        try {
            String[] answer = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/search/users?q="
                    + java.net.URLEncoder.encode(query, "UTF-8") + "&limit=12");
            return answer[1] == null ? Collections.emptyList() : parseArtists(answer[1]);
        } catch (Exception ex) {
            Logger.printInfo(() -> "My Wave: artists not found: " + ex);
            return Collections.emptyList();
        }
    }

    /** The uploaders of the newest likes: the first artists offered, before anything is typed. Blocking. */
    static List<Artist> fromLikes() {
        try {
            String[] me = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/me");
            if (me[1] == null) return Collections.emptyList();
            long userId = new JSONObject(me[1]).getLong("id");
            String[] likes = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/users/" + userId + "/track_likes?limit=60");
            if (likes[1] == null) return Collections.emptyList();
            List<Artist> artists = parseArtists(likes[1]);
            return artists.subList(0, Math.min(artists.size(), 15));
        } catch (Exception ex) {
            Logger.printInfo(() -> "My Wave: the artists of the likes could not be read: " + ex);
            return Collections.emptyList();
        }
    }
}
