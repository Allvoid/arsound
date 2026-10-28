package app.revanced.extension.soundcloud.settings;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * A bottom sheet in the look of the chosen theme: a card as wide as the screen that rises from the bottom edge, with a
 * grip on top, rounded upper corners, and a body that scrolls when it is taller than a part of the screen. A tap outside
 * it, the back button and a pull down on the grip close it.
 * <p>
 * Unlike {@link app.revanced.extension.soundcloud.shared.ArsoundDialog}, which is a card in the middle with a heavy
 * veil, a sheet leaves most of the screen above it visible (the veil is light, or none), so the screen can show what
 * the sheet changes while it is open. Used by {@link ColorPickerSheet} and by the filters of {@link WallhavenScreen}.
 */
final class ThemeSheet {
    private final Activity activity;
    private CharSequence title;
    private View content;
    private float dim = .25f;
    private float maxHeight = .62f;
    private Runnable onDismiss;
    private Dialog dialog;
    private LinearLayout card;

    ThemeSheet(Activity activity) {
        this.activity = activity;
    }

    ThemeSheet setTitle(CharSequence title) {
        this.title = title;
        return this;
    }

    ThemeSheet setContent(View content) {
        this.content = content;
        return this;
    }

    /** How much the screen behind is darkened, 0 to 1. */
    ThemeSheet setDim(float dim) {
        this.dim = dim;
        return this;
    }

    /** The tallest the sheet grows, as a part of the screen's height; a taller content scrolls. */
    ThemeSheet setMaxHeight(float fraction) {
        this.maxHeight = fraction;
        return this;
    }

    /** Called once when the sheet is gone, however it was closed. */
    ThemeSheet setOnDismiss(Runnable onDismiss) {
        this.onDismiss = onDismiss;
        return this;
    }

    Dialog show() {
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        if (onDismiss != null) dialog.setOnDismissListener(d -> onDismiss.run());

        card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        float radius = dp(26);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ArsoundTheme.palette(activity, "dialog"));
        shape.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        int accent = ArsoundTheme.palette(activity, "special");
        shape.setStroke(Math.max(1, dp(1)), (accent & 0x00FFFFFF) | 0x40000000);
        card.setBackground(shape);
        card.setElevation(dp(8));
        card.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, 0, 0, Math.max(dp(12), insets.getSystemWindowInsetBottom()));
            return insets;
        });

        card.addView(createGrip());
        if (title != null) {
            TextView titleView = new TextView(activity);
            int style = Utils.getResourceIdentifier(ResourceType.STYLE, "H3.Primary");
            if (style != 0) titleView.setTextAppearance(style);
            titleView.setTextColor(ArsoundTheme.palette(activity, "primary"));
            titleView.setText(title);
            titleView.setPadding(dp(20), 0, dp(20), dp(8));
            card.addView(titleView);
        }
        int limit = Math.round(activity.getResources().getDisplayMetrics().heightPixels * maxHeight);
        ScrollView scroll = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            }
        };
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (content != null) {
            if (content.getParent() instanceof ViewGroup) ((ViewGroup) content.getParent()).removeView(content);
            scroll.addView(content);
        }
        card.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(dim);
            window.setWindowAnimations(0);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        if (activity.isFinishing() || activity.isDestroyed()) return dialog;
        card.setTranslationY(dp(700));
        try {
            dialog.show();
        } catch (WindowManager.BadTokenException ex) {
            return dialog;
        }
        card.animate().translationY(0).setDuration(240).setInterpolator(new DecelerateInterpolator(2f)).start();
        return dialog;
    }

    /** Slides the sheet down and closes it. */
    void dismiss() {
        if (dialog == null || !dialog.isShowing()) return;
        card.animate().translationY(card.getHeight()).setDuration(160).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (dialog.isShowing() && !activity.isDestroyed()) dialog.dismiss();
                }).start();
    }

    /** The grip on top of the sheet: a short bar, and a pull down on it closes the sheet. */
    private View createGrip() {
        LinearLayout area = new LinearLayout(activity);
        area.setGravity(Gravity.CENTER);
        View bar = new View(activity);
        GradientDrawable barShape = new GradientDrawable();
        barShape.setColor((ArsoundTheme.palette(activity, "secondary") & 0x00FFFFFF) | 0x80000000);
        barShape.setCornerRadius(dp(2));
        bar.setBackground(barShape);
        area.addView(bar, new LinearLayout.LayoutParams(dp(40), dp(4)));
        area.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        float[] startY = new float[1];
        area.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY[0] = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    card.setTranslationY(Math.max(0f, event.getRawY() - startY[0]));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (card.getTranslationY() > dp(70)) dismiss();
                    else card.animate().translationY(0).setDuration(140).start();
                    return true;
                default:
                    return false;
            }
        });
        return area;
    }

    private int dp(float value) {
        return Dimens.dp(activity, value);
    }
}
