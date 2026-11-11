package app.revanced.extension.soundcloud.settings.search;

import java.util.List;

/**
 * One row of the settings for the search: where it is, its name, what it says and the extra words people use for it.
 * Filled while the screens are built (see SearchRecorder in the settings package); the words are cut into tokens once.
 */
public final class SearchEntry {
    /** The screen the row is on, or for a section row the screen it opens. */
    public final String screen;
    public final String section;
    public final String title;
    public final String description;
    /** The row of a section on the main screen: opening it needs no highlight. */
    public final boolean sectionRow;

    private final List<Token> titleTokens;
    private final List<Token> sectionTokens;
    private final List<Token> descriptionTokens;
    private final String titleKey;
    private String keywords = "";
    private volatile List<Token> keywordTokens;
    private Synonyms phraseSource;

    public SearchEntry(String screen, String section, String title, String description, boolean sectionRow) {
        this.screen = screen;
        this.section = section;
        this.title = title;
        this.description = description == null ? "" : description;
        this.sectionRow = sectionRow;
        titleTokens = Text.tokens(title);
        sectionTokens = Text.tokens(section);
        descriptionTokens = Text.tokens(this.description);
        titleKey = join(titleTokens);
    }

    /** Words that people use for this row but the row does not say, such as "хранилище" for the memory section. */
    public void addKeywords(String words) {
        if (words == null || words.isEmpty()) return;
        keywords = keywords.isEmpty() ? words : keywords + " " + words;
        keywordTokens = null;
    }

    /** Lets the rows be found by the phrases of the table; called when the entry joins an index. */
    void attachPhrases(Synonyms synonyms) {
        phraseSource = synonyms;
        synonyms.appendPhraseTokens(titleTokens);
        synonyms.appendPhraseTokens(sectionTokens);
        synonyms.appendPhraseTokens(descriptionTokens);
        keywordTokens = null;
    }

    List<Token> tokens(Field field) {
        switch (field) {
            case TITLE:
                return titleTokens;
            case SECTION:
                return sectionTokens;
            case DESCRIPTION:
                return descriptionTokens;
            default:
                // Made whole before it is kept: look-ups run off the main thread, and one of them saw the words
                // without the phrases while another was still adding them.
                List<Token> tokens = keywordTokens;
                if (tokens == null) {
                    tokens = Text.tokens(keywords);
                    if (phraseSource != null) phraseSource.appendPhraseTokens(tokens);
                    keywordTokens = tokens;
                }
                return tokens;
        }
    }

    /** The title as normalized words joined by a space. */
    String titleKey() {
        return titleKey;
    }

    private static String join(List<Token> tokens) {
        StringBuilder joined = new StringBuilder();
        for (Token token : tokens) joined.append(joined.length() == 0 ? "" : " ").append(token.norm);
        return joined.toString();
    }

    /** The parts of a row a query word can be found in, with what a find there is worth. */
    enum Field {
        TITLE(100, true),
        KEYWORD(80, true),
        /** Every row of a section carries its name, so only the words themselves count, not their synonyms. */
        SECTION(40, false),
        DESCRIPTION(25, true);

        final int base;
        final boolean widensByMeaning;

        Field(int base, boolean widensByMeaning) {
            this.base = base;
            this.widensByMeaning = widensByMeaning;
        }
    }
}
