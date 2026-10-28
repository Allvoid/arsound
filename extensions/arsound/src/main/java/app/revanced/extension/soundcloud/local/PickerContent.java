package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Dimens;

/** The whole content of the window: fits the list to the height the dialog allows, below the parts that stay in view. */
final class PickerContent extends LinearLayout {
    CappedScroll scroll;

    PickerContent(Context context) {
        super(context);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (scroll != null) {
            int fixed = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child == scroll || child.getVisibility() == GONE) continue;
                measureChild(child, widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                fixed += child.getMeasuredHeight();
            }
            int room = ArsoundDialog.bodyMaxHeight(getContext()) - fixed;
            scroll.limit = Math.max(Dimens.dp(getContext(), 120), room);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
