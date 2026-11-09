package app.revanced.extension.soundcloud.transfer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the answers of YouTube Music's web client (InnerTube "browse", WEB_REMIX): the tracks of a playlist or of the
 * liked music, page by page, and the playlists of the library. The answers are deep trees whose layout YouTube changes
 * now and then, so the parts are looked up by their names wherever they stand, not by a fixed path. Pure: no network
 * and no Android.
 */
final class YouTubeMusicParser {
    /** The tracks of one page and the token of the next page, null on the last one. */
    static final class TrackPage {
        final String title;
        final List<SourceTrack> tracks;
        final String continuation;
        /** Whether the page had a list of tracks at all: a playlist that is not there has none. */
        final boolean hasList;

        TrackPage(String title, List<SourceTrack> tracks, String continuation, boolean hasList) {
            this.title = title;
            this.tracks = tracks;
            this.continuation = continuation;
            this.hasList = hasList;
        }
    }

    /** The playlists of one page of the library, and the token of the next page. */
    static final class PlaylistPage {
        final List<String[]> playlists;
        final String continuation;

        PlaylistPage(List<String[]> playlists, String continuation) {
            this.playlists = playlists;
            this.continuation = continuation;
        }
    }

    private static final List<String> SHELVES = Arrays.asList("musicPlaylistShelfRenderer", "musicShelfRenderer");
    private static final List<String> SHELF_CONTINUATIONS = Arrays.asList("musicPlaylistShelfContinuation",
            "musicShelfContinuation");
    private static final List<String> HEADERS = Arrays.asList("musicResponsiveHeaderRenderer",
            "musicDetailHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer");
    /** "(Official Video)", "[Lyrics]", "(Official Audio)" and the like: words of the upload, not of the song. */
    private static final Pattern UPLOAD_NOTE = Pattern.compile(
            "\\s*[(\\[][^()\\[\\]]*\\b(?:official|video|audio|lyrics?|visuali[sz]er|m/?v|hd|hq|4k|клип|премьера)\\b[^()\\[\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SONG_COUNT = Pattern.compile("(\\d[\\d,. ]*)\\s*(?:songs?|tracks?|трек)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Browse ids of the library that are not playlists of tracks: the liked music (listed apart) and episodes. */
    private static final List<String> NOT_PLAYLISTS = Arrays.asList("VLLM", "VLSE");

    private YouTubeMusicParser() {
    }

    /**
     * Whether the answer was given to a signed-in account, from the "logged_in" YouTube reports with each answer.
     * Null if the answer does not say.
     */
    static Boolean loggedIn(JSONObject response) {
        JSONObject context = response.optJSONObject("responseContext");
        JSONArray services = context == null ? null : context.optJSONArray("serviceTrackingParams");
        for (int i = 0; services != null && i < services.length(); i++) {
            JSONArray params = services.optJSONObject(i) == null ? null : services.optJSONObject(i).optJSONArray("params");
            for (int j = 0; params != null && j < params.length(); j++) {
                JSONObject param = params.optJSONObject(j);
                if (param != null && "logged_in".equals(param.optString("key"))) return "1".equals(param.optString("value"));
            }
        }
        return null;
    }

    /** The first page of a playlist or of the liked music. */
    static TrackPage firstPage(JSONObject response) {
        JSONObject shelf = null;
        for (String name : SHELVES) {
            shelf = find(response, name);
            if (shelf != null) break;
        }
        String title = title(response);
        if (shelf == null) return new TrackPage(title, new ArrayList<>(), null, false);
        List<SourceTrack> tracks = new ArrayList<>();
        String next = readItems(shelf.optJSONArray("contents"), tracks);
        if (next == null) next = nextContinuationData(shelf);
        return new TrackPage(title, tracks, next, true);
    }

    /** A later page, from the token of the page before. */
    static TrackPage nextPage(JSONObject response) {
        List<SourceTrack> tracks = new ArrayList<>();
        String next = null;
        JSONArray actions = response.optJSONArray("onResponseReceivedActions");
        for (int i = 0; actions != null && i < actions.length(); i++) {
            JSONObject action = actions.optJSONObject(i);
            JSONObject append = action == null ? null : action.optJSONObject("appendContinuationItemsAction");
            if (append == null) continue;
            String token = readItems(append.optJSONArray("continuationItems"), tracks);
            if (token != null) next = token;
        }
        // The older layout of the same answer.
        JSONObject contents = response.optJSONObject("continuationContents");
        for (int i = 0; contents != null && i < SHELF_CONTINUATIONS.size(); i++) {
            JSONObject shelf = contents.optJSONObject(SHELF_CONTINUATIONS.get(i));
            if (shelf == null) continue;
            String token = readItems(shelf.optJSONArray("contents"), tracks);
            if (token == null) token = nextContinuationData(shelf);
            if (token != null) next = token;
        }
        return new TrackPage("", tracks, next, true);
    }

    /** Adds the tracks of the items; the token of the next page if the last item is one. */
    private static String readItems(JSONArray items, List<SourceTrack> tracks) {
        String next = null;
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            JSONObject row = item.optJSONObject("musicResponsiveListItemRenderer");
            if (row != null) {
                SourceTrack track = track(row);
                if (track != null) tracks.add(track);
                continue;
            }
            JSONObject more = item.optJSONObject("continuationItemRenderer");
            if (more != null) {
                JSONObject command = path(more, "continuationEndpoint", "continuationCommand");
                String token = command == null ? "" : command.optString("token", "");
                if (!token.isEmpty()) next = token;
            }
        }
        return next;
    }

    private static String nextContinuationData(JSONObject shelf) {
        JSONArray continuations = shelf.optJSONArray("continuations");
        JSONObject first = continuations == null ? null : continuations.optJSONObject(0);
        JSONObject data = first == null ? null : first.optJSONObject("nextContinuationData");
        String token = data == null ? "" : data.optString("continuation", "");
        return token.isEmpty() ? null : token;
    }

    /** One row of a list: the title, the artists, the length and the video. */
    static SourceTrack track(JSONObject row) {
        JSONArray columns = row.optJSONArray("flexColumns");
        JSONArray titleRuns = runs(columns, 0);
        String title = join(titleRuns).trim();
        if (title.isEmpty()) return null;
        JSONObject watch = titleRuns == null || titleRuns.optJSONObject(0) == null ? null
                : path(titleRuns.optJSONObject(0), "navigationEndpoint", "watchEndpoint");
        String videoId = "";
        JSONObject item = row.optJSONObject("playlistItemData");
        if (item != null) videoId = item.optString("videoId", "");
        if (videoId.isEmpty() && watch != null) videoId = watch.optString("videoId", "");
        JSONObject config = watch == null ? null
                : path(watch, "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig");
        String type = config == null ? "" : config.optString("musicVideoType", "");

        List<String> artists = artists(runs(columns, 1));
        // An upload of a listener ("UGC") names the channel, and has the artist in the title: "Artist - Song".
        boolean upload = type.endsWith("_UGC") || artists.isEmpty();
        int dash = title.indexOf(" - ");
        if (upload && dash > 0 && dash < title.length() - 3) {
            artists = splitArtists(title.substring(0, dash));
            title = title.substring(dash + 3);
        }
        title = UPLOAD_NOTE.matcher(title).replaceAll("").trim();
        if (title.isEmpty()) return null;
        long durationMs = duration(row);
        String id = videoId.isEmpty()
                ? "yt?:" + (String.join(", ", artists) + " — " + title).toLowerCase(Locale.ROOT)
                : "yt:" + videoId;
        return new SourceTrack(id, artists, title, null, durationMs);
    }

    /** The artists of the second column: the runs that link to an artist or a channel, or else its text. */
    private static List<String> artists(JSONArray runs) {
        List<String> linked = new ArrayList<>();
        for (int i = 0; runs != null && i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run == null || path(run, "navigationEndpoint", "browseEndpoint") == null) continue;
            String name = run.optString("text", "").trim();
            if (!name.isEmpty()) linked.add(name);
        }
        if (!linked.isEmpty()) return linked;
        String text = join(runs);
        int dot = text.indexOf(" • ");
        return splitArtists(dot < 0 ? text : text.substring(0, dot));
    }

    private static List<String> splitArtists(String text) {
        List<String> names = new ArrayList<>();
        for (String name : text.split(",|\\s&\\s|\\sx\\s|\\sfeat\\.\\s|\\sft\\.\\s")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        return names;
    }

    /** The length in the fixed column: "3:55" or "1:02:03"; 0 if there is none. */
    private static long duration(JSONObject row) {
        JSONArray fixed = row.optJSONArray("fixedColumns");
        JSONObject column = fixed == null || fixed.optJSONObject(0) == null ? null
                : fixed.optJSONObject(0).optJSONObject("musicResponsiveListItemFixedColumnRenderer");
        JSONObject text = column == null ? null : column.optJSONObject("text");
        if (text == null) return 0;
        return parseDuration(text.has("simpleText") ? text.optString("simpleText") : join(text.optJSONArray("runs")));
    }

    static long parseDuration(String text) {
        if (text == null || !text.trim().matches("\\d{1,4}(:\\d{1,2}){1,2}")) return 0;
        long seconds = 0;
        for (String part : text.trim().split(":")) seconds = seconds * 60 + Long.parseLong(part);
        return seconds * 1000;
    }

    /** The title of the playlist, from its header or else the page's description. */
    private static String title(JSONObject response) {
        for (String name : HEADERS) {
            JSONObject header = find(response, name);
            JSONObject title = header == null ? null : header.optJSONObject("title");
            String text = title == null ? "" : join(title.optJSONArray("runs")).trim();
            if (!text.isEmpty()) return text;
        }
        JSONObject micro = path(response, "microformat", "microformatDataRenderer");
        return micro == null ? "" : micro.optString("title", "").trim();
    }

    /**
     * The playlists of a page of the library, as {browse id without "VL", title, count or ""}. The liked music is left
     * out: it is offered on its own, first.
     */
    static PlaylistPage playlists(JSONObject response) {
        List<String[]> found = new ArrayList<>();
        collectPlaylists(response, found);
        String next = null;
        JSONObject grid = find(response, "gridRenderer");
        if (grid == null) grid = find(response, "gridContinuation");
        if (grid != null) {
            next = nextContinuationData(grid);
            JSONArray items = grid.optJSONArray("items");
            JSONObject last = items == null ? null : items.optJSONObject(items.length() - 1);
            JSONObject more = last == null ? null : last.optJSONObject("continuationItemRenderer");
            JSONObject command = more == null ? null : path(more, "continuationEndpoint", "continuationCommand");
            if (command != null && !command.optString("token", "").isEmpty()) next = command.optString("token");
        }
        return new PlaylistPage(found, next);
    }

    private static void collectPlaylists(Object node, List<String[]> found) {
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) collectPlaylists(array.opt(i), found);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject object = (JSONObject) node;
        JSONObject tile = object.optJSONObject("musicTwoRowItemRenderer");
        if (tile != null) {
            JSONObject browse = path(tile, "navigationEndpoint", "browseEndpoint");
            String browseId = browse == null ? "" : browse.optString("browseId", "");
            JSONObject title = tile.optJSONObject("title");
            String name = title == null ? "" : join(title.optJSONArray("runs")).trim();
            if (browseId.startsWith("VL") && browseId.length() > 2 && !NOT_PLAYLISTS.contains(browseId) && !name.isEmpty()) {
                JSONObject subtitle = tile.optJSONObject("subtitle");
                Matcher count = SONG_COUNT.matcher(subtitle == null ? "" : join(subtitle.optJSONArray("runs")));
                found.add(new String[]{browseId.substring(2), name,
                        count.find() ? count.group(1).replaceAll("[^0-9]", "") : ""});
            }
            return;
        }
        for (Iterator<String> keys = object.keys(); keys.hasNext(); ) collectPlaylists(object.opt(keys.next()), found);
    }

    // region Tree

    /** The first object under this name anywhere in the tree, depth first; null if there is none. */
    static JSONObject find(Object node, String name) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject direct = object.optJSONObject(name);
            if (direct != null) return direct;
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                JSONObject found = find(object.opt(keys.next()), name);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = find(array.opt(i), name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JSONObject path(JSONObject from, String... names) {
        JSONObject at = from;
        for (String name : names) {
            if (at == null) return null;
            at = at.optJSONObject(name);
        }
        return at;
    }

    private static JSONArray runs(JSONArray columns, int index) {
        JSONObject column = columns == null ? null : columns.optJSONObject(index);
        JSONObject renderer = column == null ? null : column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        JSONObject text = renderer == null ? null : renderer.optJSONObject("text");
        return text == null ? null : text.optJSONArray("runs");
    }

    private static String join(JSONArray runs) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; runs != null && i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) text.append(run.optString("text", ""));
        }
        return text.toString();
    }

    // endregion
}
