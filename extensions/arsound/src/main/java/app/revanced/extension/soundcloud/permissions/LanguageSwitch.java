package app.revanced.extension.soundcloud.permissions;

import static app.revanced.extension.soundcloud.permissions.WelcomeViews.rounded;
import static app.revanced.extension.soundcloud.permissions.WelcomeViews.textView;
import static app.revanced.extension.soundcloud.shared.Colors.inverse;
import static app.revanced.extension.soundcloud.shared.Dimens.dpTruncated;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * "EN | RU", in the look of the SoundCloud / Arsound switch of the search: a soft frame, the chosen half filled
 * with the text colour. The fill slides to the other half.
 */
final class LanguageSwitch {
    private LanguageSwitch() {
    }

    /** @param onChange Told {@code true} when Russian is picked, {@code false} for English. */
    static View create(Context context, Consumer<Boolean> onChange) {
        int textColor = ArsoundTheme.palette(context, "primary");
        int half = dpTruncated(context, 44), height = dpTruncated(context, 32);

        FrameLayout frame = new FrameLayout(context);
        frame.setTag(ArsoundDialog.KEEP_COLORS);
        int inset = dpTruncated(context, 3);
        frame.setPadding(inset, inset, inset, inset);
        frame.setBackground(rounded(context, (textColor & 0x00FFFFFF) | 0x1A000000, 11));

        View fill = new View(context);
        fill.setBackground(rounded(context, textColor, 9));
        frame.addView(fill, new FrameLayout.LayoutParams(half, height));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        TextView english = textView(context, "EN", 13, true);
        TextView russian = textView(context, "RU", 13, true);
        for (TextView label : new TextView[]{english, russian}) {
            label.setGravity(Gravity.CENTER);
            labels.addView(label, new LinearLayout.LayoutParams(half, height));
        }
        frame.addView(labels);

        int chosenColor = inverse(textColor), otherColor = (textColor & 0x00FFFFFF) | 0xB0000000;
        english.setTextColor(chosenColor);
        russian.setTextColor(otherColor);
        boolean[] isRussian = {false};
        View.OnClickListener select = v -> {
            boolean toRussian = v == russian;
            if (toRussian == isRussian[0]) return;
            isRussian[0] = toRussian;
            fill.animate().translationX(toRussian ? half : 0).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            fade(english, toRussian ? chosenColor : otherColor, toRussian ? otherColor : chosenColor);
            fade(russian, toRussian ? otherColor : chosenColor, toRussian ? chosenColor : otherColor);
            onChange.accept(toRussian);
        };
        english.setOnClickListener(select);
        russian.setOnClickListener(select);
        return frame;
    }

    private static void fade(TextView label, int from, int to) {
        ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        animator.setDuration(200);
        animator.addUpdateListener(a -> label.setTextColor((int) a.getAnimatedValue()));
        animator.start();
    }
}
