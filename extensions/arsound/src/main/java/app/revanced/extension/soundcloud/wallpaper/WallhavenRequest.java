package app.revanced.extension.soundcloud.wallpaper;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Writes the part of a Wallhaven search address after the question mark. Pure text with no Android and no network, so
 * the JVM tests can check exactly what the service is asked for: the picture sizes and the sorting decide how many
 * pictures a search can find at all.
 */
final class WallhavenRequest {
    private WallhavenRequest() {
    }

    /**
     * @param sorting The sorting to ask for, already decided ({@link WallhavenRules#sorting}).
     * @param range   The period of the top list; only sent for that sorting.
     * @param purity  The purity mask, three digits as bits.
     * @param seed    For a random sorting: the same seed gives the following pages without repeats; may be empty.
     */
    static String params(String text, String sorting, String range, int categories, int purity, String seed,
                         WallhavenRules.Shape shape, int page) throws UnsupportedEncodingException {
        StringBuilder url = new StringBuilder("categories=").append(mask(categories))
                .append("&purity=").append(mask(purity))
                .append("&sorting=").append(sorting).append("&order=desc")
                .append("&page=").append(Math.max(1, page));
        String typed = text == null ? "" : text.trim();
        if (!typed.isEmpty()) url.append("&q=").append(URLEncoder.encode(typed, "UTF-8"));
        if (Wallhaven.TOPLIST.equals(sorting)) url.append("&topRange=").append(range);
        if (Wallhaven.RANDOM.equals(sorting) && seed != null && !seed.isEmpty()) url.append("&seed=").append(seed);
        return url.append(shapeParams(shape)).toString();
    }

    /**
     * What a shape asks for. The sizes keep out the pictures too small for the screen; "portrait" and "landscape" are the
     * service's own words for tall and wide ones.
     */
    static String shapeParams(WallhavenRules.Shape shape) {
        switch (shape) {
            case TALL:
                return "&ratios=portrait&atleast=" + atLeast(WallhavenRules.TALL_MIN_WIDTH, WallhavenRules.TALL_MIN_HEIGHT);
            case WIDE_FOR_PHONE:
                // Only wide ones: the tall ones were all listed before.
                return "&ratios=landscape&atleast="
                        + atLeast(WallhavenRules.WIDE_FOR_PHONE_MIN_WIDTH, WallhavenRules.WIDE_FOR_PHONE_MIN_HEIGHT);
            default:
                return "&atleast=" + atLeast(WallhavenRules.WIDE_MIN_WIDTH, WallhavenRules.WIDE_MIN_HEIGHT);
        }
    }

    private static String atLeast(int width, int height) {
        return width + "x" + height;
    }

    /** The category and purity masks, as the API writes them: three digits, the first one is the highest bit. */
    static String mask(int bits) {
        return "" + (bits >> 2 & 1) + (bits >> 1 & 1) + (bits & 1);
    }
}
