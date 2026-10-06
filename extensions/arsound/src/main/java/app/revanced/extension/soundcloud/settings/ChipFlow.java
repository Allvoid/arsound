package app.revanced.extension.soundcloud.settings;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays chips out in lines, starting a new line when the next one does not fit. */
final class ChipFlow extends ViewGroup {
    private final int gap;

    ChipFlow(Context context, int gap) {
        super(context);
        this.gap = gap;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (x > 0 && x + child.getMeasuredWidth() > width) {
                x = 0;
                y += lineHeight + gap;
                lineHeight = 0;
            }
            x += child.getMeasuredWidth() + gap;
            lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), y + lineHeight + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            if (x > 0 && x + child.getMeasuredWidth() > width) {
                x = 0;
                y += lineHeight + gap;
                lineHeight = 0;
            }
            child.layout(getPaddingLeft() + x, getPaddingTop() + y,
                    getPaddingLeft() + x + child.getMeasuredWidth(), getPaddingTop() + y + child.getMeasuredHeight());
            x += child.getMeasuredWidth() + gap;
            lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
        }
    }
}
