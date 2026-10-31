package app.revanced.extension.soundcloud.search;

import android.content.res.ColorStateList;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;

/**
 * The arrow in the search field is a button the size of the field (a square of 48 dp), and its press was a rounded
 * square much bigger than the arrow. The press becomes a circle just around the arrow, in the colour of the text.
 */
final class SearchBackButton {
    /** The circle of the press, in dp. */
    private static final int CIRCLE_DP = 36;

    private SearchBackButton() {
    }

    /** The button made round last, so the layout passes that follow do not look for it again. */
    private static WeakReference<View> done = new WeakReference<>(null);

    /** Gives the arrow of the search field a round press; does nothing if the field has no such button. */
    static void round(View coordinator, int buttonId, TextView field) {
        try {
            View finished = done.get();
            if (finished != null && finished.isAttachedToWindow() && finished.getTag() == SearchBackButton.class) return;
            View button = coordinator.findViewById(buttonId);
            if (button == null || button.getWidth() == 0 || button.getTag() == SearchBackButton.class) return;
            int inset = Math.max(0, (Math.min(button.getWidth(), button.getHeight()) - Math.round(Dimens.dpExact(button.getContext(), CIRCLE_DP))) / 2);
            ShapeDrawable mask = new ShapeDrawable(new OvalShape());
            RippleDrawable ripple = new RippleDrawable(
                    ColorStateList.valueOf(Colors.withAlpha(field.getCurrentTextColor(), 0x40)), null, mask);
            button.setBackground(new InsetDrawable(ripple, inset));
            button.setTag(SearchBackButton.class);
            done = new WeakReference<>(button);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not round the press of the search arrow", ex);
        }
    }
}
