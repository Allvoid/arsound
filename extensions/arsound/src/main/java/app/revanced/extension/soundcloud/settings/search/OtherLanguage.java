package app.revanced.extension.soundcloud.settings.search;

import java.util.List;

/**
 * Lets the search find a row by the words of both languages. The screens are read twice: in the language of the app
 * and in the other one. The rows come out in the same order both times, so the n-th row of the second reading says
 * the same as the n-th row of the first, in the other language; its words are added to the first as extra words.
 * Has no Android parts, so it is tested on the JVM.
 */
public final class OtherLanguage {
    private OtherLanguage() {
    }

    /**
     * Adds what the rows of {@code other} say to the rows of {@code main}, one to one.
     *
     * @return Whether the readings matched. If the screens gave a different number of rows in the two languages,
     * nothing is added: pairing the wrong rows would make the search find rows by words they do not hold.
     */
    public static boolean merge(List<SearchEntry> main, List<SearchEntry> other) {
        if (main.size() != other.size()) return false;
        for (int i = 0; i < main.size(); i++) {
            SearchEntry target = main.get(i);
            SearchEntry source = other.get(i);
            // The same text in both languages (a name such as "DNS") adds nothing.
            if (!source.title.equals(target.title)) target.addKeywords(source.title);
            if (!source.description.equals(target.description)) target.addKeywords(source.description);
            // The section name makes every row of the section found by it, as the first reading does.
            target.addKeywords(source.keywords());
            if (source.section != null && !source.section.equals(target.section)) target.addKeywords(source.section);
        }
        return true;
    }
}
