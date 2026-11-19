package app.revanced.extension.soundcloud.library;

/** Whether a list is at its top, from the index of its first visible row ({@link LikesListScroll}). Pure logic. */
final class ListTop {
    /** At the top: the first row is the first one. */
    static final Integer TOP = 0;
    /** Scrolled down by at least one row. */
    static final Integer BELOW = 1;

    private ListTop() {
    }

    /**
     * @param firstVisibleRow The index of the first visible row, as the screen's calculation gives it.
     * @return {@link #TOP} for 0, {@link #BELOW} for any other number: equal values for every row below the top, so the
     * state that holds it does not change while the list scrolls. Anything that is not a number comes back as it is.
     */
    static Object collapse(Object firstVisibleRow) {
        if (!(firstVisibleRow instanceof Number)) return firstVisibleRow;
        return ((Number) firstVisibleRow).intValue() == 0 ? TOP : BELOW;
    }
}
