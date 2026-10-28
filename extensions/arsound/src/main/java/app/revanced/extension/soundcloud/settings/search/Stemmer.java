package app.revanced.extension.soundcloud.settings.search;

import java.util.Arrays;
import java.util.Comparator;

/**
 * A light stemmer that cuts word endings, so that "памяти" and "память", "downloads" and "downloaded" meet. It is
 * not a full Snowball: it only has to be consistent. Cyrillic words get the Russian rules, Latin words the English
 * ones, anything with digits is kept as it is.
 */
public final class Stemmer {
    private static final String[] RUSSIAN_ENDINGS = longestFirst(
            // Adjectives and participles.
            "ыми", "ими", "ого", "его", "ому", "ему", "ая", "яя", "ое", "ее", "ые", "ие", "ый", "ий", "ой", "ую", "юю",
            "ом", "ем", "ым", "им", "их", "ых", "ею", "ою", "ей",
            // Nouns.
            "иями", "ями", "ами", "иям", "ям", "ием", "ам", "ах", "иях", "ях", "ов", "ев", "ье", "ию", "ью", "ия", "ии", "ья",
            "а", "у", "о", "е", "ы", "и", "я", "ю",
            // The few verb forms that settings use.
            "ете", "йте", "ешь");
    private static final String[] ENGLISH_SUFFIXES = {"ing", "ed", "er", "ly"};
    private static final String[] ENGLISH_PLURAL_ES = {"sses", "xes", "ches", "shes", "zes"};

    private Stemmer() {
    }

    private static String[] longestFirst(String... endings) {
        String[] sorted = endings.clone();
        Arrays.sort(sorted, Comparator.comparingInt(String::length).reversed());
        return sorted;
    }

    /** The stem of a lower-case word without "ё". */
    public static String stem(String word) {
        boolean cyrillic = false;
        boolean latin = false;
        boolean other = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c >= 'а' && c <= 'я') cyrillic = true;
            else if (c >= 'a' && c <= 'z') latin = true;
            else other = true;
        }
        if (other || cyrillic == latin) return word;
        return cyrillic ? russian(word) : english(word);
    }

    private static String russian(String word) {
        if (word.length() < 4) return word;
        String stem = word;
        if ((stem.endsWith("ся") || stem.endsWith("сь")) && stem.length() - 2 >= 3) {
            stem = stem.substring(0, stem.length() - 2);
        }
        for (String ending : RUSSIAN_ENDINGS) {
            if (stem.endsWith(ending) && stem.length() - ending.length() >= 3) {
                stem = stem.substring(0, stem.length() - ending.length());
                break;
            }
        }
        if (stem.endsWith("ь") || stem.endsWith("й")) stem = stem.substring(0, stem.length() - 1);
        // A fleeting vowel: "загрузок" and "загрузка" both end up as "загрузк", "песен" and "песня" as "песн".
        if (stem.length() >= 5 && (stem.endsWith("ок") || stem.endsWith("ек") || stem.endsWith("ен"))) {
            stem = stem.substring(0, stem.length() - 2) + stem.charAt(stem.length() - 1);
        }
        if (stem.endsWith("нн")) stem = stem.substring(0, stem.length() - 1);
        return stem;
    }

    private static String english(String word) {
        if (word.length() < 4) return word;
        String stem = word;
        if (stem.endsWith("ies") && stem.length() > 4) {
            stem = stem.substring(0, stem.length() - 3) + "y";
        } else if (endsWithAny(stem, ENGLISH_PLURAL_ES)) {
            stem = stem.substring(0, stem.length() - 2);
        } else if (stem.endsWith("s") && !stem.endsWith("ss") && !stem.endsWith("us") && !stem.endsWith("is")
                && stem.length() - 1 >= 3) {
            stem = stem.substring(0, stem.length() - 1);
        }
        // Twice, so that "buffering" (-ing, then -er) and "buffer" end up in the same stem.
        for (int pass = 0; pass < 2; pass++) {
            boolean cut = false;
            for (String suffix : ENGLISH_SUFFIXES) {
                if (stem.endsWith(suffix) && stem.length() - suffix.length() >= 3) {
                    stem = stem.substring(0, stem.length() - suffix.length());
                    int last = stem.length() - 1;
                    // "setting" -> "sett" -> "set".
                    if (last >= 2 && stem.charAt(last) == stem.charAt(last - 1) && "bdgmnprt".indexOf(stem.charAt(last)) >= 0) {
                        stem = stem.substring(0, last);
                    }
                    cut = true;
                    break;
                }
            }
            if (!cut) break;
        }
        if (stem.length() > 4 && stem.endsWith("e")) stem = stem.substring(0, stem.length() - 1);
        return stem;
    }

    private static boolean endsWithAny(String word, String[] endings) {
        for (String ending : endings) if (word.endsWith(ending)) return true;
        return false;
    }
}
