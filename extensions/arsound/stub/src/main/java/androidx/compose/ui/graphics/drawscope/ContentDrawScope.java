package androidx.compose.ui.graphics.drawscope;

public interface ContentDrawScope {
    void drawContent();

    /** Declared by DrawScope, which this interface extends in the app. */
    DrawContext getDrawContext();
}
