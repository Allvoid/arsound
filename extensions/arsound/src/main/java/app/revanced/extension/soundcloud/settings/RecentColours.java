package app.revanced.extension.soundcloud.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The colours the user picked lately, newest first, for the "recent" row of the colour picker. Pure list logic with no
 * Android: the picker keeps the list as one text in a setting ({@link #KEY}) and reads it back with {@link #parse}.
 */
final class RecentColours {
    /** The setting that keeps the list. */
    static final String KEY = "arsound_custom_theme_recent";
    /** How many colours are kept. */
    static final int MAX = 8;

    private RecentColours() {
    }

    /** The colours of a stored text: #RRGGBB in capitals, the damaged and repeated ones left out, at most {@link #MAX}. */
    static List<String> parse(String stored) {
        List<String> colours = new ArrayList<>();
        if (stored == null) return colours;
        for (String part : stored.split(",")) {
            String hex = normal(part);
            if (hex != null && !colours.contains(hex) && colours.size() < MAX) colours.add(hex);
        }
        return colours;
    }

    /** The text to keep for a list. */
    static String store(List<String> colours) {
        StringBuilder text = new StringBuilder();
        for (String colour : colours) {
            if (text.length() > 0) text.append(',');
            text.append(colour);
        }
        return text.toString();
    }

    /** The list with this colour put first; it moves up if it was there already. A text that is not a colour changes nothing. */
    static List<String> withAdded(List<String> colours, String hex) {
        String added = normal(hex);
        List<String> result = new ArrayList<>();
        if (added == null) {
            result.addAll(colours);
            return result;
        }
        result.add(added);
        for (String colour : colours) {
            if (!colour.equals(added) && result.size() < MAX) result.add(colour);
        }
        return result;
    }

    /** #RRGGBB in capitals, or null when the text is not a colour of six hex digits. */
    private static String normal(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.length() != 7 || trimmed.charAt(0) != '#') return null;
        for (int i = 1; i < 7; i++) {
            char c = trimmed.charAt(i);
            boolean digit = c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
            if (!digit) return null;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
