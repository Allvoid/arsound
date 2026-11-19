package app.revanced.extension.soundcloud.library;

import kotlin.jvm.functions.Function0;

/**
 * Smooth scrolling of "Your likes" (patch {@code library/LikesListScrollPatch.kt}).
 * <p>
 * The screen keeps {@code derivedStateOf { listState.firstVisibleItemIndex }} and reads it while it is composed, only to
 * tell whether the list is at its top. Every new first row then composed the whole screen again, and with it every
 * visible row, several times a second while the list was scrolled. Here the same calculation gives only "at the top"
 * (0) or "below it" (1), so the screen is composed again only when the list reaches or leaves its top.
 */
@SuppressWarnings("unused")
public final class LikesListScroll {
    private LikesListScroll() {
    }

    /**
     * Injection point, in {@code TrackLikesScreenKt.a}, right before the screen makes its derived state.
     *
     * @param firstVisibleRow The calculation of the index of the first visible row.
     * @return The same calculation, reduced to {@link ListTop#collapse}. It still reads the list's state, so the derived
     * state follows the list as before.
     */
    public static Function0<Object> atTopOnly(Function0<?> firstVisibleRow) {
        if (firstVisibleRow == null) return null;
        return () -> ListTop.collapse(firstVisibleRow.invoke());
    }
}
