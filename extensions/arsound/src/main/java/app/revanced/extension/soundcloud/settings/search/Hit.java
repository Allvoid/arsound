package app.revanced.extension.soundcloud.settings.search;

import java.util.List;

/** A row found by a query: how well it fits, what to colour in its title, and why it was found. */
public final class Hit {
    public final SearchEntry entry;
    public final int score;
    /** Pairs of start and end in the title, one pair per coloured piece. */
    public final List<int[]> titleRanges;
    /** Why the row was found; {@link Reason#EXACT} needs no words. */
    public final Reason reason;
    /** The word of the query that explains the {@link #reason}, as typed. Empty for an exact find. */
    public final String queryWord;
    /** The word of the row that explains the {@link #reason}; for a mistake or a layout, the word that was meant. */
    public final String matchedWord;

    Hit(SearchEntry entry, int score, List<int[]> titleRanges, Reason reason, String queryWord, String matchedWord) {
        this.entry = entry;
        this.score = score;
        this.titleRanges = titleRanges;
        this.reason = reason;
        this.queryWord = queryWord;
        this.matchedWord = matchedWord;
    }
}
