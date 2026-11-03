package app.revanced.extension.soundcloud.wallpaper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import app.revanced.extension.soundcloud.shared.StrictJson;

/**
 * Turns the answer of the Commons search into a {@link WallpaperSource.Page} of pictures. Pure: it reads a text and builds
 * objects, with no Android and no network, so the JVM tests feed it a recorded answer.
 * <p>
 * The answer lists the files in no fixed order, each with a number {@code index} that is its place in the ranking, so the
 * pictures are sorted by it. A picture is kept only when it is a JPEG or a PNG of the size and shape the stage wants
 * ({@link CommonsRules#keeps}) and both its addresses are on the Wikimedia servers ({@link CommonsRules#isImageHost}):
 * the addresses come out of the answer, and nothing else is ever fetched.
 * <p>
 * Commons has no safe search, so a file whose title or one of whose categories holds an unwanted word is dropped
 * ({@link CommonsRules#hasUnwantedWord}). The categories come in the same answer; when the service had to cut their list
 * (one answer carries 500 at most), the files after the cut are dropped too, because what they are in is not known.
 */
final class CommonsResponse {
    /** Deeper nesting than a Commons answer ever has (about six levels); a reply that is deeper is not one. */
    private static final int MAX_JSON_DEPTH = 40;

    private CommonsResponse() {
    }

    /**
     * @param json   The body of the answer.
     * @param shape  The stage the search was made for.
     * @param page   The page asked for, from 1.
     * @param source The source the pictures are from.
     * @throws WallpaperSource.Failure For an answer that is an error of the service.
     * @throws IOException             For a text that is not JSON ({@link StrictJson.Malformed}: a page of a proxy or a
     *                                 captive portal) or not the answer of the service.
     */
    static WallpaperSource.Page parse(String json, WallhavenRules.Shape shape, int page, WallpaperSource source)
            throws IOException {
        Object root = StrictJson.parse(json, MAX_JSON_DEPTH);
        if (StrictJson.object(root) == null) throw new IOException("Commons sent a reply that is not a JSON object");

        Object error = StrictJson.get(root, "error");
        if (error != null) {
            String code = StrictJson.string(StrictJson.get(error, "code"), "");
            // A limit of the service is a wait; anything else is a failure of its own.
            throw new WallpaperSource.Failure("Commons", code.contains("ratelimit") || code.contains("maxlag") ? 429 : 500, 0);
        }

        long cutAt = categoriesCutAt(StrictJson.get(root, "continue"));
        List<Entry> entries = new ArrayList<>();
        List<Object> pages = StrictJson.array(StrictJson.get(StrictJson.get(root, "query"), "pages"));
        for (int i = 0; pages != null && i < pages.size(); i++) {
            Entry entry = entry(pages.get(i), shape, source, cutAt);
            if (entry != null) entries.add(entry);
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.index < b.index ? -1 : (a.index == b.index ? 0 : 1);
            }
        });
        if (shape == WallhavenRules.Shape.WIDE_FOR_PHONE) entries = tallFirst(entries);

        WallpaperSource.Page result = new WallpaperSource.Page();
        for (Entry entry : entries) result.items.add(entry.item);
        result.page = page;
        // The answer names where the next page begins while there is one; the pages are counted by the caller.
        boolean more = StrictJson.get(root, "continue") != null && page < CommonsRules.MAX_PAGES;
        result.lastPage = more ? page + 1 : page;
        result.total = result.items.size();
        return result;
    }

    /**
     * The page id the list of categories was cut at, or -1 when it was not cut. The service lists the categories of the
     * files in the order of their page ids and stops after its limit; the answer then says where it goes on
     * ({@code clcontinue}: the page id, a bar and the next category). The files before that id are complete, the file of
     * that id and the ones after it are not. A token that cannot be read says nothing is known, so every file is dropped.
     */
    private static long categoriesCutAt(Object continuation) {
        Object token = StrictJson.get(continuation, "clcontinue");
        if (token == null) return -1;
        String text = StrictJson.string(token, "");
        int bar = text.indexOf('|');
        try {
            return Math.max(0, Long.parseLong(bar < 0 ? text : text.substring(0, bar)));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** True when the title of the file or one of its categories holds an unwanted word. */
    private static boolean unwanted(Object file, String title) {
        if (CommonsRules.hasUnwantedWord(title)) return true;
        List<Object> categories = StrictJson.array(StrictJson.get(file, "categories"));
        for (int i = 0; categories != null && i < categories.size(); i++) {
            if (CommonsRules.hasUnwantedWord(StrictJson.string(StrictJson.get(categories.get(i), "title"), ""))) return true;
        }
        return false;
    }

    /** A kept picture with its place in the ranking and its size. */
    private static final class Entry {
        final long index;
        final int width, height;
        final Wallhaven.Item item;

        Entry(long index, int width, int height, Wallhaven.Item item) {
            this.index = index;
            this.width = width;
            this.height = height;
            this.item = item;
        }
    }

    private static List<Entry> tallFirst(List<Entry> entries) {
        int[] widths = new int[entries.size()], heights = new int[entries.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = entries.get(i).width;
            heights[i] = entries.get(i).height;
        }
        List<Entry> ordered = new ArrayList<>(entries.size());
        for (int index : CommonsRules.tallFirstOrder(widths, heights)) ordered.add(entries.get(index));
        return ordered;
    }

    /**
     * The picture of one file of the answer, or null when it is not one to offer.
     *
     * @param cutAt Where the list of categories was cut, or -1; see {@link #categoriesCutAt}.
     */
    private static Entry entry(Object file, WallhavenRules.Shape shape, WallpaperSource source, long cutAt) {
        if (StrictJson.object(file) == null) return null;
        long pageId = StrictJson.number(StrictJson.get(file, "pageid"), 0);
        String title = StrictJson.string(StrictJson.get(file, "title"), "");
        if (pageId <= 0 && title.isEmpty()) return null;
        if (cutAt >= 0 && (pageId <= 0 || pageId >= cutAt)) return null;
        if (unwanted(file, title)) return null;
        List<Object> infos = StrictJson.array(StrictJson.get(file, "imageinfo"));
        Object info = infos == null || infos.isEmpty() ? null : infos.get(0);
        if (StrictJson.object(info) == null) return null;

        if (!CommonsRules.isUsableMime(StrictJson.string(StrictJson.get(info, "mime"), ""))) return null;
        int width = (int) StrictJson.number(StrictJson.get(info, "width"), 0);
        int height = (int) StrictJson.number(StrictJson.get(info, "height"), 0);
        if (width <= 0 || height <= 0 || !CommonsRules.keeps(shape, width, height)) return null;

        String original = StrictJson.string(StrictJson.get(info, "url"), "");
        String thumbnail = CommonsRules.stripQuery(StrictJson.string(StrictJson.get(info, "thumburl"), ""));
        String full = CommonsRules.fullUrl(StrictJson.string(StrictJson.get(info, "thumburl"), ""), original, width, height);
        // The addresses are the answer's word: only the Wikimedia servers are followed.
        if (!CommonsRules.isImageHost(thumbnail) || !CommonsRules.isImageHost(full)) return null;

        // The prefix keeps an id from ever meeting one of Wallhaven's in the caches that both share.
        String id = "commons" + (pageId > 0 ? String.valueOf(pageId) : Integer.toHexString(title.hashCode()));

        Object meta = StrictJson.get(info, "extmetadata");
        String author = CommonsRules.author(metaValue(meta, "Artist"));
        String licence = CommonsRules.stripHtml(metaValue(meta, "LicenseShortName"), 40);
        String description = CommonsRules.stripHtml(metaValue(meta, "ImageDescription"), CommonsRules.MAX_DESCRIPTION);
        String page = StrictJson.string(StrictJson.get(info, "descriptionurl"), "");

        long originalBytes = StrictJson.number(StrictJson.get(info, "size"), 0);
        long estimate = CommonsRules.estimateBytes(originalBytes, width, CommonsRules.fullWidth(width, height));
        // fileSize 0: the grid's sharp stage (SharpLoader) only runs for pictures that say a size, and a cell of the grid
        // is no wider than the 330 pixel thumbnail, so the sharp stage would only cost the service traffic.
        Wallhaven.Item item = new Wallhaven.Item(source, id, "sfw", width + "x" + height, thumbnail, full, 0, estimate, 0, 0,
                CommonsRules.isPageUrl(page) ? page : "", author, licence, description);
        return new Entry(StrictJson.number(StrictJson.get(file, "index"), Long.MAX_VALUE), width, height, item);
    }

    private static String metaValue(Object meta, String name) {
        Object entry = StrictJson.get(meta, name);
        return entry instanceof Map ? StrictJson.string(StrictJson.get(entry, "value"), "") : "";
    }
}
