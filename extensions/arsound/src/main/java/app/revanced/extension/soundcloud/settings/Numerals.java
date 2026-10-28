package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

/** Counts with their words, in the form Russian wants after the number. */
final class Numerals {
    private Numerals() {
    }

    /** "1 день", "3 дня", "30 дней"; a year for 365 days. In English "1 day", "3 days". */
    static String days(long days) {
        if (days == 365) return text("Год", "A year");
        return text(days + " " + russianForm(days, "день", "дня", "дней"), days + (days == 1 ? " day" : " days"));
    }

    /** "1 трек", "3 трека", "5 треков". */
    static String russianTracks(long count) {
        return count + " " + russianForm(count, "трек", "трека", "треков");
    }

    /** The form of a word after a number: one (1, 21), few (2-4, 22-24), many (the rest, and 11-14). */
    static String russianForm(long count, String one, String few, String many) {
        long tens = count % 100, ones = count % 10;
        return tens >= 11 && tens <= 14 ? many : ones == 1 ? one : ones >= 2 && ones <= 4 ? few : many;
    }
}
