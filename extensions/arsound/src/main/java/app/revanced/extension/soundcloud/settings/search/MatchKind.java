package app.revanced.extension.soundcloud.settings.search;

/** How a query word met a word of a row, and how much that is worth compared with the word typed exactly. */
enum MatchKind {
    /** The row's word starts with the query word. */
    DIRECT_START(1.0),
    /** The query word and the row's word have the same stem ("уведомления" and "уведомлении"). */
    STEM(0.95),
    /** The query word sits inside the row's word. */
    DIRECT_PART(0.7),
    /** The words mean the same ("память" and "хранилище"). */
    SYNONYM(0.75),
    /** The words are near in meaning ("память" and "кэш"). */
    RELATED(0.45);

    final double factor;

    MatchKind(double factor) {
        this.factor = factor;
    }
}
