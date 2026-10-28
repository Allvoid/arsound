package app.revanced.extension.soundcloud.settings.search;

/** One word of a text: where it stands in the original, the lower-case form and the stem. Made by {@link Text#tokens}. */
public final class Token {
    /** Start and end (exclusive) in the original text. */
    public final int start;
    public final int end;
    /** Lower case, "ё" as "е". */
    public final String norm;
    /** The stem of {@link #norm}, see {@link Stemmer}. */
    public final String stem;

    /**
     * True for a made-up token that stands for two neighbouring words which form a phrase of the {@link SynonymTable}
     * ("не нравится"); it is found only through the synonyms, never by its letters.
     */
    public final boolean phrase;

    Token(int start, int end, String norm, String stem) {
        this(start, end, norm, stem, false);
    }

    Token(int start, int end, String norm, String stem, boolean phrase) {
        this.start = start;
        this.end = end;
        this.norm = norm;
        this.stem = stem;
        this.phrase = phrase;
    }
}
