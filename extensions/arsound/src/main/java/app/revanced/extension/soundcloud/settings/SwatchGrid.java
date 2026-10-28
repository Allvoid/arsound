package app.revanced.extension.soundcloud.settings;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import app.revanced.extension.soundcloud.shared.Dimens;

/**
 * Lays {@link ThemeSwatch} views out in rows that wrap, as many to a row as fit, so every ready colour is on screen
 * without scrolling sideways. Used by {@link ColorPickerSheet} for the recent colours and the named presets.
 */
final class SwatchGrid extends ViewGroup {
    private final int cell;
    private int perRow = 1;

    SwatchGrid(Context context) {
        super(context);
        cell = Dimens.dp(context, 48);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        perRow = Math.max(1, width / cell);
        int count = getChildCount();
        int rows = (count + perRow - 1) / perRow;
        int exact = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY);
        for (int i = 0; i < count; i++) getChildAt(i).measure(exact, exact);
        setMeasuredDimension(width, rows * cell);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // The spare width is shared between the swatches of a row, so the rows end at the same edge.
        int spare = perRow > 1 ? (getWidth() - perRow * cell) / (perRow - 1) : 0;
        int step = cell + Math.max(0, spare);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int x = (i % perRow) * step;
            int y = (i / perRow) * cell;
            child.layout(x, y, x + cell, y + cell);
        }
    }
}
