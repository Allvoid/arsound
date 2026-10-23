package app.revanced.extension.soundcloud.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every row of the settings screens, for the search on the main screen: where it is, how it is called and what it
 * says. The rows are collected while the screens are built, so a new setting is found without being listed anywhere.
 */
final class SettingsSearchIndex {
    static final class Entry {
        /** The screen the row is on, or for a section row the screen it opens. */
        final String screen;
        final String section;
        final String title;
        final String description;
        /** The row of a section on the main screen: opening it needs no highlight. */
        final boolean sectionRow;

        private final String titleKey;
        private final String sectionKey;
        private final String descriptionKey;

        Entry(String screen, String section, String title, String description, boolean sectionRow) {
            this.screen = screen;
            this.section = section;
            this.title = title;
            this.description = description == null ? "" : description;
            this.sectionRow = sectionRow;
            titleKey = normalize(title);
            sectionKey = normalize(section);
            descriptionKey = normalize(this.description);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    void add(Entry entry) {
        entries.add(entry);
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Rows that contain every word of the query, the best matches first. */
    List<Entry> search(String query, int limit) {
        List<String> words = words(query);
        if (words.isEmpty()) return Collections.emptyList();

        List<Entry> found = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (Entry entry : entries) {
            int score = score(entry, words);
            if (score <= 0) continue;
            // Stable insertion by score: equal scores keep the order of the screens.
            int at = found.size();
            while (at > 0 && scores.get(at - 1) < score) at--;
            found.add(at, entry);
            scores.add(at, score);
        }
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    private static int score(Entry entry, List<String> words) {
        int total = 0;
        for (String word : words) {
            int best;
            if (startsWord(entry.titleKey, word)) best = 100;
            else if (entry.titleKey.contains(word)) best = 70;
            else if (startsWord(entry.sectionKey, word)) best = 40;
            else if (startsWord(entry.descriptionKey, word)) best = 25;
            else if (entry.descriptionKey.contains(word)) best = 15;
            else return 0;
            total += best;
        }
        // The row of a section leads when its name is what was typed.
        return entry.sectionRow ? total + 5 : total;
    }

    private static boolean startsWord(String text, String word) {
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + 1)) {
            if (at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1))) return true;
        }
        return false;
    }

    static List<String> words(String query) {
        List<String> words = new ArrayList<>();
        for (String word : normalize(query).split("\\s+")) if (!word.isEmpty()) words.add(word);
        return words;
    }

    /**
     * Lower case, "ё" as "е". One character stays one character, so a position found in the result is also
     * the position in the original text.
     */
    static String normalize(String text) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            result.append(c == 'ё' ? 'е' : c);
        }
        return result.toString();
    }

    /** Where each word of the query starts and ends in the title, to colour it. Pairs of start and end. */
    static List<int[]> matches(String title, String query) {
        List<int[]> ranges = new ArrayList<>();
        String key = normalize(title);
        for (String word : words(query)) {
            int at = key.indexOf(word);
            if (at >= 0) ranges.add(new int[]{at, at + word.length()});
        }
        return ranges;
    }
}
