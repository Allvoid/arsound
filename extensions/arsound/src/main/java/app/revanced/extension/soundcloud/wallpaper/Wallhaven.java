package app.revanced.extension.soundcloud.wallpaper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Lang;

/**
 * The client of Wallhaven's open API (wallhaven.cc/help/api): searching wallpapers and checking an API key. It is also the
 * first {@link WallpaperSource} ({@link #SOURCE}); the second is {@link CommonsSource}. Downloads and picture reads go through
 * {@link WallpaperHttp}, which checks the host of every address against the source that owns it.
 * <p>
 * No key is needed for safe pictures. A key, if the user has one, goes only in the {@code X-API-Key} header of calls
 * to the API itself: never in an address, never to the picture servers, never in the log. Sketchy and NSFW pictures
 * are asked for only with a key and the user's explicit switch ({@link #adultAllowed()}). The service allows 45 calls
 * a minute; the calls are spread out and an answer 429 is reported with the wait it asked for.
 * <p>
 * How wide the catalogue is depends on what a search asks for ({@link WallhavenRules}, {@link WallhavenRequest}): a text
 * is looked for by relevance, not in the top list of a period; a browse is by the most saved pictures; and tall pictures
 * are followed by big wide ones, whose middle fits a phone, when they run out.
 */
public final class Wallhaven {
    private static final String SITE = "https://wallhaven.cc/";
    private static final String API = SITE + "api/v1/";
    /** 45 calls a minute are allowed; a call every 1.4 seconds stays under that. */
    private static final long REQUEST_GAP_MS = 1_400;
    /** An answer of the API (a page of results) is never bigger than this. */
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024;

    public static final String TOPLIST = "toplist";
    public static final String DATE_ADDED = "date_added";
    public static final String VIEWS = "views";
    public static final String FAVORITES = "favorites";
    public static final String RELEVANCE = "relevance";
    public static final String RANDOM = "random";
    /** The sortings in the order the browser offers them: the one it starts with is first. */
    public static final String[] SORTINGS = {FAVORITES, TOPLIST, VIEWS, DATE_ADDED, RELEVANCE, RANDOM};
    /** The periods of the top list. */
    public static final String[] RANGES = {"1d", "3d", "1w", "1M", "3M", "6M", "1y"};
    /** A year: the top list of a month holds only about 300 tall pictures, a year about 4 000. */
    private static final String DEFAULT_RANGE = "1y";
    /** What {@link Query#toJson} writes as its version; a note of the last search with no version is from an older build. */
    private static final int JSON_VERSION = 2;

    /** The category and purity masks, as the API writes them: three digits, the first one is the highest bit. */
    public static final int GENERAL = 4, ANIME = 2, PEOPLE = 1;
    public static final int SFW = 4, SKETCHY = 2, NSFW = 1;

    private static final RequestPacer PACER = new RequestPacer(REQUEST_GAP_MS);

    /** Wallhaven as a source of the catalogue: what the screen and the grid talk to. */
    public static final WallpaperSource SOURCE = new Source();

    private Wallhaven() {
    }

    /** The address of a picture's page on the site, for the link to the author's page. */
    public static String pageUrl(String id) {
        return SITE + "w/" + id;
    }

    // region Key

    /** The user's API key, or an empty string. Keep it out of logs and addresses. */
    public static String key() {
        return clean(Settings.getString(Settings.WALLHAVEN_KEY, ""));
    }

    /** Only letters and digits are left: a key pasted with a space or a line break must not break the header. */
    public static String clean(String key) {
        if (key == null) return "";
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') kept.append(c);
        }
        return kept.length() > 64 ? "" : kept.toString();
    }

    /** True when sketchy and NSFW pictures may be asked for: a key is set and the user switched it on. */
    public static boolean adultAllowed() {
        return !key().isEmpty() && Settings.getBoolean(Settings.WALLHAVEN_ADULT, false);
    }

    /** What a key check found. */
    public enum KeyState {
        VALID, INVALID, UNKNOWN
    }

    /** Asks the API for the account's settings, which only a good key can read. Blocks. */
    public static KeyState checkKey(String key) {
        try {
            HttpURLConnection connection = open(API + "settings", key);
            try {
                int code = connection.getResponseCode();
                if (code == 200) return KeyState.VALID;
                return code == 401 ? KeyState.INVALID : KeyState.UNKNOWN;
            } finally {
                connection.disconnect();
            }
        } catch (IOException ex) {
            Logger.printInfo(() -> "Wallhaven: the key could not be checked: " + ex.getClass().getSimpleName());
            return KeyState.UNKNOWN;
        }
    }

    // endregion

    // region Search

    /** What the browser asks for. Fields are changed in place by the screen and saved as the last search. */
    public static final class Query {
        public String text = "";
        /**
         * What the sorting chips show. Unless {@link #sortingChosen} is set the app decides it ({@link #settleSorting}):
         * a text is looked for by relevance, a browse is by the most saved pictures.
         */
        public String sorting = WallhavenRules.DEFAULT_SORTING;
        /** The user tapped a sorting chip, so that sorting is kept for a text too. Saved with the last search. */
        public boolean sortingChosen;
        public String range = DEFAULT_RANGE;
        public int categories = GENERAL | ANIME | PEOPLE;
        /**
         * Pictures for a phone held upright: tall ones, and big wide ones after them when the tall ones run out
         * ({@link #widened}). False: only wide pictures, as the user chose.
         */
        public boolean portrait = true;
        /**
         * Set by the browser, not saved: the tall pictures of this search are used up, so the pages that follow are wide
         * pictures ({@link WallhavenRules.Shape#WIDE_FOR_PHONE}), whose middle fits the phone.
         */
        public boolean widened;
        /** What the adult mode adds to the safe pictures, when it is on. */
        public int adultPurity = SKETCHY | NSFW;
        /** For a random sorting: the same seed gives the following pages without repeats. */
        public String seed = "";
        /**
         * Commons only: the set searched ({@link CommonsRules#FEATURED} or {@link CommonsRules#QUALITY}). Saved with the
         * last search. The words, the shape and the widening are shared by every source; the rest of this class is Wallhaven's.
         */
        public String commonsSet = CommonsRules.DEFAULT_SET;
        /** Wallhaven only; set by the browser, not saved: the user chose to search without the key the service refused. */
        public boolean withoutKey;

        public Query copy() {
            Query copy = new Query();
            copy.text = text;
            copy.sorting = sorting;
            copy.sortingChosen = sortingChosen;
            copy.range = range;
            copy.categories = categories;
            copy.portrait = portrait;
            copy.widened = widened;
            copy.adultPurity = adultPurity;
            copy.seed = seed;
            copy.commonsSet = commonsSet;
            copy.withoutKey = withoutKey;
            return copy;
        }

        /** The purity mask to ask for: safe pictures, and the others only when the adult mode allows them. */
        public int purity(boolean adult) {
            return SFW | (adult ? adultPurity & (SKETCHY | NSFW) : 0);
        }

        /** The sorting this search really uses: the chosen one, else relevance for a text and the favourites for a browse. */
        public String effectiveSorting() {
            return WallhavenRules.sorting(text, sorting, sortingChosen);
        }

        /** Makes {@link #sorting} say what the search uses, so the chips show it. Call it after the text changed. */
        public void settleSorting() {
            sorting = effectiveSorting();
        }

        /** The user tapped this sorting chip: it is kept from now on, whatever the text. */
        public void chooseSorting(String chosen) {
            sorting = chosen;
            sortingChosen = true;
        }

        /** Gives the sorting back to the app (relevance for a text, favourites for a browse). */
        public void useAutomaticSorting() {
            sortingChosen = false;
            settleSorting();
        }

        public String toJson() {
            try {
                return new JSONObject().put("v", JSON_VERSION).put("q", text).put("sorting", sorting)
                        .put("chosen", sortingChosen).put("range", range).put("categories", categories)
                        .put("portrait", portrait).put("adult", adultPurity).put("set", commonsSet).toString();
            } catch (Exception ex) {
                return "{}";
            }
        }

        public static Query fromJson(String json) {
            Query query = new Query();
            try {
                JSONObject object = new JSONObject(json);
                boolean current = object.optInt("v", 1) >= JSON_VERSION;
                query.text = object.optString("q", "");
                String sorting = object.optString("sorting", WallhavenRules.DEFAULT_SORTING);
                for (String known : SORTINGS) if (known.equals(sorting)) query.sorting = sorting;
                String saved = object.optString("range", DEFAULT_RANGE);
                // An older build always saved the period; its default of a month left only a few hundred tall pictures.
                String range = !current && "1M".equals(saved) ? DEFAULT_RANGE : saved;
                for (String known : RANGES) if (known.equals(range)) query.range = range;
                query.sortingChosen = current ? object.optBoolean("chosen", false)
                        : WallhavenRules.legacySortingWasChosen(query.sorting, saved, !query.text.isEmpty());
                int categories = object.optInt("categories", GENERAL | ANIME | PEOPLE) & 7;
                if (categories != 0) query.categories = categories;
                query.portrait = object.optBoolean("portrait", true);
                query.adultPurity = object.optInt("adult", SKETCHY | NSFW) & (SKETCHY | NSFW);
                query.commonsSet = CommonsRules.validSet(object.optString("set", CommonsRules.DEFAULT_SET));
            } catch (Exception ex) {
                // A damaged note of the last search starts the browser from its defaults.
            }
            query.settleSorting();
            return query;
        }
    }

    /**
     * One picture of the results, of whichever source ({@link #source}). The class keeps the name from the time Wallhaven was
     * the only source: the grid's loaders ({@code ThumbnailLoader}, {@code SharpLoader}) are written against it.
     */
    public static final class Item {
        /** Where the picture comes from: it knows the hosts to fetch from and the lines the preview shows. */
        public final WallpaperSource source;
        public final String id;
        public final String purity;
        public final String resolution;
        public final String thumbnail;
        public final String fullUrl;
        /**
         * The size in bytes of the full file, which the grid may fetch to make a cell sharp ({@code SharpPolicy}); 0 when the
         * source offers no such file (its thumbnail is as wide as a cell, so there is nothing to sharpen).
         */
        public final long fileSize;
        /** How many bytes the preview downloads: {@link #fileSize} when that is known, else an estimate, else 0. */
        public final long previewBytes;
        public final int views;
        public final int favorites;
        /** The address of the picture's page on the site, or an empty string. */
        public final String pageUrl;
        /** Who made the picture, the licence and a short description; empty for Wallhaven, which gives none. */
        public final String author, licence, description;

        Item(WallpaperSource source, String id, String purity, String resolution, String thumbnail, String fullUrl,
             long fileSize, long previewBytes, int views, int favorites, String pageUrl, String author, String licence,
             String description) {
            this.source = source;
            this.id = id;
            this.purity = purity;
            this.resolution = resolution;
            this.thumbnail = thumbnail;
            this.fullUrl = fullUrl;
            this.fileSize = fileSize;
            this.previewBytes = previewBytes;
            this.views = views;
            this.favorites = favorites;
            this.pageUrl = pageUrl;
            this.author = author;
            this.licence = licence;
            this.description = description;
        }

        public boolean isSafe() {
            return "sfw".equals(purity);
        }

        /** True for a picture wider than it is tall: in a grid of tall cells only its middle shows. */
        public boolean isWide() {
            return WallhavenRules.isWide(resolution);
        }

        /** What a screen reader says of the picture: its description when it has one, else its size. */
        public String label() {
            return description.isEmpty() ? resolution : description;
        }
    }

    /** The picture of one entry of Wallhaven's answer. */
    private static Item parseItem(JSONObject json) {
        String id = json.optString("id");
        JSONObject thumbs = json.optJSONObject("thumbs");
        // The "original" one keeps the picture's shape (the long side is 300 pixels); the others are cut to a frame.
        String thumbnail = thumbs == null ? "" : thumbs.optString("original", thumbs.optString("large"));
        long fileSize = json.optLong("file_size");
        return new Item(SOURCE, id, json.optString("purity", "sfw"), json.optString("resolution"), thumbnail,
                json.optString("path"), fileSize, fileSize, json.optInt("views"), json.optInt("favorites"), pageUrl(id),
                "", "", "");
    }

    /**
     * Searches. Blocks: call it off the main thread.
     *
     * @param key The API key, or an empty string to search as a guest.
     * @throws WallpaperSource.Failure For an answer that is not 200: 401 for a key the service does not know, 429 for too
     *                                  many calls.
     */
    public static WallpaperSource.Page search(Query query, int page, String key) throws IOException {
        boolean adult = !key.isEmpty() && Settings.getBoolean(Settings.WALLHAVEN_ADULT, false);
        String params = WallhavenRequest.params(query.text, query.effectiveSorting(), query.range, query.categories,
                query.purity(adult), query.seed, WallhavenRules.shape(query.portrait, query.widened), page);

        String body = readText(API + "search?" + params, key);
        try {
            JSONObject json = new JSONObject(body);
            WallpaperSource.Page result = new WallpaperSource.Page();
            JSONArray data = json.optJSONArray("data");
            for (int i = 0; data != null && i < data.length(); i++) {
                Item item = parseItem(data.getJSONObject(i));
                // The addresses come from the service's answer: only its own hosts are followed.
                if (!item.id.isEmpty() && isWallhavenHost(item.fullUrl) && isWallhavenHost(item.thumbnail)) result.items.add(item);
            }
            JSONObject meta = json.optJSONObject("meta");
            result.page = meta == null ? page : meta.optInt("current_page", page);
            result.lastPage = meta == null ? page : meta.optInt("last_page", page);
            result.total = meta == null ? result.items.size() : meta.optInt("total", result.items.size());
            result.seed = meta == null || meta.isNull("seed") ? "" : meta.optString("seed", "");
            return result;
        } catch (JSONException ex) {
            // A page from a proxy or a captive portal instead of an answer.
            throw new IOException("Wallhaven sent a reply that is not JSON");
        }
    }

    /** A call to the API: spaced out, with the key in the header. Returns the body. */
    private static String readText(String address, String key) throws IOException {
        HttpURLConnection connection = open(address, key);
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                // No wait named for an answer 429: the screen picks one.
                throw new WallpaperSource.Failure("Wallhaven", code, code == 429 ? WallpaperHttp.retryAfter(connection) : 0);
            }
            try (InputStream input = connection.getInputStream()) {
                return new String(WallpaperHttp.readAll(input, MAX_JSON_BYTES), "UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Opens a call to the API after the pause that keeps it under the limit. The key goes in a header, and only to
     * Wallhaven's own hosts: a redirect anywhere else is refused before it is followed.
     */
    private static HttpURLConnection open(String address, String key) throws IOException {
        RegionGuard.throwIfBlockedAnyHost();
        PACER.pause();
        return WallpaperHttp.openFollowing(address, 10_000, 15_000, Wallhaven::isWallhavenHost,
                key != null && !key.isEmpty() ? "X-API-Key" : null, key);
    }

    // endregion

    // region Pictures

    /** True for an address on Wallhaven's own hosts, over https. */
    static boolean isWallhavenHost(String address) {
        try {
            URL url = new URL(address);
            String host = url.getHost().toLowerCase(java.util.Locale.US);
            return "https".equals(url.getProtocol()) && (host.equals("wallhaven.cc") || host.endsWith(".wallhaven.cc"));
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Reads a small picture (a thumbnail) of any source. Not counted against the API limit, so not spaced out. Blocks.
     * The name is the one the grid's loaders know it by.
     */
    static byte[] readThumbnail(String address) throws IOException {
        return readPicture(address, MAX_THUMBNAIL_BYTES);
    }

    /**
     * Reads a picture of the source whose host the address is on into memory, up to {@code limit} bytes: a bigger one is
     * refused, from its announced size or when it grows over the limit. An address that is on no source's host is refused.
     * Not counted against the API limit, so not spaced out. Blocks.
     */
    static byte[] readPicture(String address, int limit) throws IOException {
        WallpaperSource owner = WallpaperSources.owning(address);
        if (owner == null) throw new IOException("Not the address of a wallpaper source");
        return WallpaperHttp.readPicture(owner, address, limit);
    }

    // endregion

    // region Source

    /** Wallhaven as a {@link WallpaperSource}: the calls of this class under the interface the screen uses. */
    private static final class Source implements WallpaperSource {
        @Override
        public String id() {
            return "wallhaven";
        }

        @Override
        public String name() {
            return "Wallhaven";
        }

        @Override
        public boolean curatedOnly() {
            return false;
        }

        @Override
        public Page search(Query query, int page) throws IOException {
            return Wallhaven.search(query, page, query.withoutKey ? "" : key());
        }

        @Override
        public int activeFilters(Query query) {
            return WallhavenRules.activeFilters(query.categories, query.portrait, adultAllowed());
        }

        @Override
        public boolean ownsHost(String address) {
            return isWallhavenHost(address);
        }

        @Override
        public List<Line> lines(Item item) {
            List<Line> lines = new ArrayList<>();
            lines.add(new Line(Lang.text(item.views + " просмотров, " + item.favorites + " в избранном",
                    item.views + " views, " + item.favorites + " favorites"), null));
            lines.add(new Line(Lang.text("Источник и автор: ", "Source and author: ") + item.pageUrl.replace("https://", ""),
                    item.pageUrl));
            return lines;
        }
    }

    // endregion
}
