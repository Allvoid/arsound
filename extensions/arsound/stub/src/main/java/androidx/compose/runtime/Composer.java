package androidx.compose.runtime;

public interface Composer {
    /** startReplaceGroup. */
    void r(int key);

    /** endReplaceGroup. */
    void o();

    /** consume: the value of a composition local at this place. */
    Object D(CompositionLocal<?> local);
}
