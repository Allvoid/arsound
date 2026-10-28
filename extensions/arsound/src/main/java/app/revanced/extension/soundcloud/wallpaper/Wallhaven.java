package app.revanced.extension.soundcloud.wallpaper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The client of Wallhaven's open API (wallhaven.cc/help/api): searching wallpapers, checking an API key and
 * downloading a picture.
 * <p>
 * No key is needed for safe pictures. A key, if the user has one, goes only in the {@code X-API-Key} header of calls
 * to the API itself: never in an address, never to the picture servers, never in the log. Sketchy and NSFW pictures
 * are asked for only with a key and the user's explicit switch ({@link #adultAllowed()}). The service allows 45 calls
 * a minute; the calls are spread out and an answer 429 is reported with the wait it asked for.
 */
public final class Wallhaven {
    private static final String SITE = "https://wallhaven.cc/";
    private static final String API = SITE + "api/v1/";
    /** 45 calls a minute are allowed; a call every 1.4 seconds stays under that. */
    private static final long REQUEST_GAP_MS = 1_400;
    private static final int MAX_DOWNLOAD_BYTES = 80 * 1024 * 1024;
    /** An answer of the API (a page of results) is never bigger than this. */
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024;

    public static final String TOPLIST = "toplist";
    public static final String DATE_ADDED = "date_added";
    public static final String VIEWS = "views";
    public static final String FAVORITES = "favorites";
    public static final String RELEVANCE = "relevance";
    public static final String RANDOM = "random";
    /** The sortings in the order the browser offers them. */
    public static final String[] SORTINGS = {TOPLIST, DATE_ADDED, VIEWS, FAVORITES, RELEVANCE, RANDOM};
    /** The periods of the top list. */
    public static final String[] RANGES = {"1d", "3d", "1w", "1M", "3M", "6M", "1y"};
    private static final String DEFAULT_RANGE = "1M";

    /** The category and purity masks, as the API writes them: three digits, the first one is the highest bit. */
    public static final int GENERAL = 4, ANIME = 2, PEOPLE = 1;
    public static final int SFW = 4, SKETCHY = 2, NSFW = 1;

    private static final Object PACE = new Object();
    private static long lastCall;

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
        public String sorting = TOPLIST;
        public String range = DEFAULT_RANGE;
        public int categories = GENERAL | ANIME | PEOPLE;
        /** Only tall pictures, which fit a phone held upright. */
        public boolean portrait = true;
        /** What the adult mode adds to the safe pictures, when it is on. */
        public int adultPurity = SKETCHY | NSFW;
        /** For a random sorting: the same seed gives the following pages without repeats. */
        public String seed = "";

        public Query copy() {
            Query copy = new Query();
            copy.text = text;
            copy.sorting = sorting;
            copy.range = range;
            copy.categories = categories;
            copy.portrait = portrait;
            copy.adultPurity = adultPurity;
            copy.seed = seed;
            return copy;
        }

        /** The purity mask to ask for: safe pictures, and the others only when the adult mode allows them. */
        public int purity(boolean adult) {
            return SFW | (adult ? adultPurity & (SKETCHY | NSFW) : 0);
        }

        public String toJson() {
            try {
                return new JSONObject().put("q", text).put("sorting", sorting).put("range", range)
                        .put("categories", categories).put("portrait", portrait).put("adult", adultPurity).toString();
            } catch (Exception ex) {
                return "{}";
            }
        }

        public static Query fromJson(String json) {
            Query query = new Query();
            try {
                JSONObject object = new JSONObject(json);
                query.text = object.optString("q", "");
                String sorting = object.optString("sorting", TOPLIST);
                for (String known : SORTINGS) if (known.equals(sorting)) query.sorting = sorting;
                String range = object.optString("range", DEFAULT_RANGE);
                for (String known : RANGES) if (known.equals(range)) query.range = range;
                int categories = object.optInt("categories", GENERAL | ANIME | PEOPLE) & 7;
                if (categories != 0) query.categories = categories;
                query.portrait = object.optBoolean("portrait", true);
                query.adultPurity = object.optInt("adult", SKETCHY | NSFW) & (SKETCHY | NSFW);
            } catch (Exception ex) {
                // A damaged note of the last search starts the browser from its defaults.
            }
            return query;
        }
    }

    /** One picture of the results. */
    public static final class Item {
        public final String id;
        public final String purity;
        public final String resolution;
        public final String thumbnail;
        public final String fullUrl;
        public final long fileSize;
        public final int views;
        public final int favorites;

        Item(JSONObject json) {
            id = json.optString("id");
            purity = json.optString("purity", "sfw");
            resolution = json.optString("resolution");
            JSONObject thumbs = json.optJSONObject("thumbs");
            // The "original" one keeps the picture's shape (the long side is 300 pixels); the others are cut to a frame.
            thumbnail = thumbs == null ? "" : thumbs.optString("original", thumbs.optString("large"));
            fullUrl = json.optString("path");
            fileSize = json.optLong("file_size");
            views = json.optInt("views");
            favorites = json.optInt("favorites");
        }

        public boolean isSafe() {
            return "sfw".equals(purity);
        }
    }

    /** One page of the results. */
    public static final class Page {
        public final List<Item> items = new ArrayList<>();
        public int page;
        public int lastPage;
        public int total;
        public String seed = "";
    }

    /** The API refused or could not answer. */
    public static final class ApiException extends IOException {
        public final int code;
        /** For an answer 429: how many seconds the service asked to wait, else 0. */
        public final int retryAfter;

        ApiException(int code, int retryAfter) {
            super("Wallhaven HTTP " + code);
            this.code = code;
            this.retryAfter = retryAfter;
        }
    }

    /**
     * Searches. Blocks: call it off the main thread.
     *
     * @param key The API key, or an empty string to search as a guest.
     * @throws ApiException For an answer that is not 200: 401 for a key the service does not know, 429 for too many calls.
     */
    public static Page search(Query query, int page, String key) throws IOException {
        boolean adult = !key.isEmpty() && Settings.getBoolean(Settings.WALLHAVEN_ADULT, false);
        StringBuilder url = new StringBuilder(API).append("search?categories=").append(mask(query.categories))
                .append("&purity=").append(mask(query.purity(adult)))
                .append("&sorting=").append(query.sorting).append("&order=desc")
                .append("&page=").append(Math.max(1, page));
        if (!query.text.isEmpty()) url.append("&q=").append(URLEncoder.encode(query.text, "UTF-8"));
        if (TOPLIST.equals(query.sorting)) url.append("&topRange=").append(query.range);
        if (RANDOM.equals(query.sorting) && !query.seed.isEmpty()) url.append("&seed=").append(query.seed);
        // Keeps out the pictures too small for the screen; "portrait" is the service's own word for tall ones.
        if (query.portrait) url.append("&ratios=portrait&atleast=1080x1920");
        else url.append("&atleast=1920x1080");

        String body = readText(url.toString(), key);
        try {
            JSONObject json = new JSONObject(body);
            Page result = new Page();
            JSONArray data = json.optJSONArray("data");
            for (int i = 0; data != null && i < data.length(); i++) {
                Item item = new Item(data.getJSONObject(i));
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

    private static String mask(int bits) {
        return "" + (bits >> 2 & 1) + (bits >> 1 & 1) + (bits & 1);
    }

    /** A call to the API: spaced out, with the key in the header. Returns the body. */
    private static String readText(String address, String key) throws IOException {
        HttpURLConnection connection = open(address, key);
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                int wait = 0;
                if (code == 429) {
                    try {
                        wait = Integer.parseInt(connection.getHeaderField("Retry-After").trim());
                    } catch (Exception ex) {
                        // No wait named: the screen picks one.
                    }
                }
                throw new ApiException(code, wait);
            }
            try (InputStream input = connection.getInputStream()) {
                return new String(readAll(input, MAX_JSON_BYTES), "UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    /** A connection to an address with the timeouts and the name the service sees; the caller reads it and disconnects. */
    private static HttpURLConnection connect(String address, int connectMs, int readMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(connectMs);
        connection.setReadTimeout(readMs);
        connection.setRequestProperty("User-Agent", "Arsound");
        return connection;
    }

    /** Opens a call to the API after the pause that keeps it under the limit. */
    private static HttpURLConnection open(String address, String key) throws IOException {
        RegionGuard.throwIfBlockedAnyHost();
        synchronized (PACE) {
            long wait = lastCall + REQUEST_GAP_MS - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted");
                }
            }
            lastCall = System.currentTimeMillis();
        }
        HttpURLConnection connection = connect(address, 10_000, 15_000);
        if (key != null && !key.isEmpty()) connection.setRequestProperty("X-API-Key", key);
        return connection;
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

    /** Reads a small picture (a thumbnail). Not counted against the API limit, so not spaced out. Blocks. */
    static byte[] readThumbnail(String address) throws IOException {
        return readPicture(address, MAX_THUMBNAIL_BYTES);
    }

    /**
     * Reads a picture of Wallhaven into memory, up to {@code limit} bytes: a bigger one is refused, from its announced
     * size or when it grows over the limit. Not counted against the API limit, so not spaced out. Blocks.
     */
    static byte[] readPicture(String address, int limit) throws IOException {
        if (!isWallhavenHost(address)) throw new IOException("Not a Wallhaven address");
        RegionGuard.throwIfBlockedAnyHost();
        HttpURLConnection connection = connect(address, 10_000, 15_000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            if (connection.getContentLength() > limit) throw new IOException("The picture is too big");
            try (InputStream input = connection.getInputStream()) {
                return readAll(input, limit);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Told how much of a picture is downloaded; the total is -1 when the server does not say. */
    public interface Progress {
        void onProgress(long done, long total);
    }

    /**
     * Downloads a picture into a file. Blocks: call it off the main thread.
     *
     * @param cancelled Set by the screen when the user gives up; the download then stops and the file is removed.
     */
    public static void download(String address, File target, Progress progress, AtomicBoolean cancelled) throws IOException {
        if (!isWallhavenHost(address)) throw new IOException("Not a Wallhaven address");
        RegionGuard.throwIfBlockedAnyHost();
        HttpURLConnection connection = connect(address, 15_000, 30_000);
        boolean done = false;
        try {
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            long total = connection.getContentLength();
            if (total > MAX_DOWNLOAD_BYTES) throw new IOException("The picture is too big");
            byte[] buffer = new byte[32 * 1024];
            long received = 0;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(target)) {
                int count;
                while ((count = input.read(buffer)) > 0) {
                    if (cancelled.get()) throw new IOException("Cancelled");
                    received += count;
                    if (received > MAX_DOWNLOAD_BYTES) throw new IOException("The picture is too big");
                    output.write(buffer, 0, count);
                    if (progress != null) progress.onProgress(received, total);
                }
            }
            done = true;
        } finally {
            connection.disconnect();
            if (!done) target.delete();
        }
    }

    private static byte[] readAll(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) > 0) {
            output.write(buffer, 0, count);
            if (output.size() > limit) throw new IOException("The answer is too big");
        }
        return output.toByteArray();
    }

    // endregion
}
