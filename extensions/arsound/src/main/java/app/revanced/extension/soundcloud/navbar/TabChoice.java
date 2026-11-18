package app.revanced.extension.soundcloud.navbar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The user's own order of the bottom bar's tabs and the tab the app starts on. Both are kept in settings and apply
 * after the app restarts: the order where the bar's list is arranged ({@link BottomTabs#arrange}), the start tab when
 * the bar is built after a launch.
 */
public final class TabChoice {
    public static final String HOME = "home", WAVE = "wave", FEED = "feed", SEARCH = "search", LIBRARY = "library";
    /** The tabs the user can place, in the order of a bar nobody changed. */
    public static final List<String> DEFAULT_ORDER = Collections.unmodifiableList(Arrays.asList(HOME, WAVE, FEED, SEARCH, LIBRARY));
    /** The order, as keys joined with commas; empty means the bar as SoundCloud has it. */
    public static final String ORDER_KEY = "tab_order";
    /** The tab the app starts on, one of the keys; empty means the first tab of the bar. */
    public static final String START_KEY = "tab_start";

    /**
     * The setting the choice row of the start tab keeps its number in (0 first, then the keys in the order of
     * {@link #DEFAULT_ORDER}); {@link #saveStart} writes the key the bar reads.
     */
    public static final String START_KEY_CODE = "tab_start_code";

    private TabChoice() {
    }

    /** The user's order, the five keys, or an empty list while the bar is as SoundCloud has it. */
    static List<String> order() {
        return parse(Settings.getString(ORDER_KEY, ""));
    }

    /** The order for the settings screen: the user's, or the usual one. */
    public static List<String> orderOrDefault() {
        List<String> order = order();
        return order.isEmpty() ? new ArrayList<>(DEFAULT_ORDER) : order;
    }

    public static void saveOrder(List<String> order) {
        Settings.putStringNow(ORDER_KEY, order.equals(DEFAULT_ORDER) ? "" : join(order));
    }

    /** The start tab key, or an empty text for "the first one". */
    public static String start() {
        String value = Settings.getString(START_KEY, "");
        return DEFAULT_ORDER.contains(value) ? value : "";
    }

    /** The key of a code of the choice row: 0 is "the first one" (empty). */
    public static String keyOfCode(long code) {
        return code >= 1 && code <= DEFAULT_ORDER.size() ? DEFAULT_ORDER.get((int) code - 1) : "";
    }

    public static void saveStart(String key) {
        Settings.putStringNow(START_KEY, key == null ? "" : key);
    }

    /** The keys of a kept text: only known ones, each once, the missing ones added at the end; empty for an empty text. */
    static List<String> parse(String saved) {
        List<String> order = new ArrayList<>();
        if (saved == null || saved.trim().isEmpty()) return order;
        for (String part : saved.split(",")) {
            String key = part.trim();
            if (DEFAULT_ORDER.contains(key) && !order.contains(key)) order.add(key);
        }
        for (String key : DEFAULT_ORDER) if (!order.contains(key)) order.add(key);
        return order;
    }

    static String join(List<String> order) {
        StringBuilder text = new StringBuilder();
        for (String key : order) {
            if (text.length() > 0) text.append(',');
            text.append(key);
        }
        return text.toString();
    }
}
