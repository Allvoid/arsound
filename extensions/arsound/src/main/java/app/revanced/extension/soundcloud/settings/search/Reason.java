package app.revanced.extension.soundcloud.settings.search;

/**
 * Why a row was found, for the line under a search result. From the clearest to the vaguest, so the larger one wins
 * when several words of a query were found in different ways.
 */
public enum Reason {
    /** The words are in the row (also in another form of the word). Nothing to explain. */
    EXACT,
    /** A word that means the same. */
    SYNONYM,
    /** A word that is near in meaning. */
    RELATED,
    /** The query was typed on the wrong keyboard layout. */
    LAYOUT,
    /** The query has a typing mistake. */
    FUZZY
}
