package app.revanced.extension.soundcloud.wallpaper;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Writes the address of a Wikimedia Commons search (the MediaWiki Action API of commons.wikimedia.org, which needs no key).
 * Pure text with no Android and no network, so the JVM tests can check exactly what the service is asked for: the filters
 * decide which pictures can be found at all.
 * <p>
 * A search is a file search ({@code gsrnamespace=6}) whose text is the words typed, the sizes of the stage
 * ({@link WallhavenRules.Shape}) and the chosen set as a category. Commons has no safe search, so a search is never made
 * without the category: only pictures the community marked as featured or as quality images are offered. Even those sets
 * hold nudes, so the search always leaves out the category "Nudity" with its subcategories, and the answer carries the
 * categories of every file for {@link CommonsResponse} to read: the first only reaches part of the subcategories (the
 * search service says so itself), the second is the check that holds.
 */
final class CommonsRequest {
    static final String API = "https://commons.wikimedia.org/w/api.php";
    /** Left out of every search, whatever was typed. */
    static final String EXCLUDED = "-deepcategory:\"Nudity\"";

    private CommonsRequest() {
    }

    /**
     * The part of the address after the question mark.
     *
     * @param text The words to look for, already in English; may be empty. They are cleaned here ({@link CommonsRules#cleanText}).
     * @param set  {@link CommonsRules#FEATURED} or {@link CommonsRules#QUALITY}.
     * @param page The page, from 1; the service is asked for the results from {@code (page - 1) * 30}.
     */
    static String params(String text, String set, WallhavenRules.Shape shape, int page) throws UnsupportedEncodingException {
        String words = CommonsRules.cleanText(text);
        StringBuilder url = new StringBuilder("action=query&format=json&formatversion=2&generator=search&gsrnamespace=6")
                .append("&gsrlimit=").append(CommonsRules.PAGE_SIZE)
                .append("&gsrsearch=").append(URLEncoder.encode(searchText(words, set, shape), "UTF-8"));
        int offset = offset(page);
        if (offset > 0) url.append("&gsroffset=").append(offset);
        // With no words there is nothing to rank by, and the order of a set is then the same few pictures every time;
        // a random order gives a good mix of what the set holds.
        if (words.isEmpty()) url.append("&gsrsort=random");
        // The categories of the files, the visible ones only (the hidden ones are the wiki's bookkeeping). "max" is 500
        // for the whole page of 30 files, well above what pages have (87 and 113 in two checks); when a page does
        // run over, the answer says where it was cut and CommonsResponse drops the files after that place.
        return url.append("&prop=").append(URLEncoder.encode("imageinfo|categories", "UTF-8"))
                .append("&iiprop=").append(URLEncoder.encode("url|size|mime|extmetadata", "UTF-8"))
                .append("&iiextmetadatafilter=")
                .append(URLEncoder.encode("Artist|LicenseShortName|AttributionRequired|ImageDescription", "UTF-8"))
                .append("&iiurlwidth=").append(CommonsRules.THUMB_WIDTH)
                .append("&cllimit=max&clshow=").append(URLEncoder.encode("!hidden", "UTF-8"))
                .toString();
    }

    /** The search words with the filters of the stage, the category of the set and the category that is left out after them. */
    static String searchText(String words, String set, WallhavenRules.Shape shape) {
        StringBuilder search = new StringBuilder();
        if (words != null && !words.isEmpty()) search.append(words).append(' ');
        return search.append("filetype:bitmap ").append(sizeFilters(shape))
                .append(" incategory:\"").append(CommonsRules.category(set)).append("\" ").append(EXCLUDED).toString();
    }

    /**
     * What a stage asks for in pixels. The sizes keep out the pictures too small for the screen; the shape itself cannot
     * be asked for and is decided on the pictures that come back ({@link CommonsRules#keeps}).
     */
    static String sizeFilters(WallhavenRules.Shape shape) {
        switch (shape) {
            case TALL:
                return "fileh:>" + (CommonsRules.TALL_MIN_HEIGHT - 1)
                        + " filew:" + CommonsRules.TALL_MIN_WIDTH + "," + CommonsRules.TALL_MAX_WIDTH;
            case WIDE_FOR_PHONE:
                // Big pictures of any shape: the tall ones among them come first (CommonsRules.tallFirstOrder).
                return "fileh:>" + (WallhavenRules.WIDE_FOR_PHONE_MIN_HEIGHT - 1)
                        + " filew:>" + (WallhavenRules.WIDE_FOR_PHONE_MIN_WIDTH - 1);
            default:
                return "fileh:>" + (WallhavenRules.WIDE_MIN_HEIGHT - 1) + " filew:>" + (WallhavenRules.WIDE_MIN_WIDTH - 1);
        }
    }

    /** Where the results of a page begin. The page is never below 1, and the service lists at most 10 000 results. */
    static int offset(int page) {
        return (Math.max(1, Math.min(page, CommonsRules.MAX_PAGES)) - 1) * CommonsRules.PAGE_SIZE;
    }
}
