package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * The notice about SoundCloud's limit of likes ({@link LikeLimit}): a card at the top of the screen in the colours of the
 * theme, like the rest of Arsound's own panels. It slides in, stays long enough to read, and goes on a tap or by itself.
 * One at a time: a new one replaces the old.
 */
final class LikeLimitBanner {
    /** Near the limit, or past it. */
    enum Kind {NEAR, BLOCKED}

    private static final String TAG = "arsound_like_limit_banner";
    private static final long VISIBLE_MS = 16_000;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private LikeLimitBanner() {
    }

    /**
     * Shows the notice on the screen that is open now. Main thread. Nothing is shown when no screen is open: the notice
     * is not kept for later, as the next like or the next refusal says it again.
     *
     * @param count How many likes were given in the last day (for {@link Kind#NEAR}).
     */
    static void show(Kind kind, int count) {
        try {
            Activity activity = UpdateChecker.resumedActivity();
            if (activity == null || activity.isFinishing()) return;
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            View old = content.findViewWithTag(TAG);
            if (old != null) content.removeView(old);

            View card = build(activity, kind, count);
            int top = topInset(content);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            params.setMargins(Dimens.dp(activity, 12), top + Dimens.dp(activity, 8), Dimens.dp(activity, 12), 0);
            content.addView(card, params);

            card.setAlpha(0f);
            card.setTranslationY(-Dimens.dp(activity, 24));
            card.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator(1.8f)).start();
            Runnable hide = () -> dismiss(content, card);
            card.setOnClickListener(v -> {
                HANDLER.removeCallbacks(hide);
                dismiss(content, card);
            });
            HANDLER.postDelayed(hide, VISIBLE_MS);
        } catch (Throwable ex) {
            Logger.printException(() -> "Like limit: could not show the notice", ex);
        }
    }

    private static void dismiss(ViewGroup content, View card) {
        if (card.getParent() != content) return;
        card.animate().alpha(0f).translationY(-Dimens.dp(card.getContext(), 16)).setDuration(200)
                .withEndAction(() -> content.removeView(card)).start();
    }

    private static int topInset(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        return insets == null ? 0 : insets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
    }

    private static View build(Context context, Kind kind, int count) {
        LinearLayout card = new LinearLayout(context);
        card.setTag(TAG);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setElevation(Dimens.dp(context, 6));
        int pad = Dimens.dp(context, 16);
        card.setPadding(pad, Dimens.dp(context, 14), pad, Dimens.dp(context, 14));

        GradientDrawable background = new GradientDrawable();
        background.setColor((ArsoundTheme.palette(context, "dialog") & 0x00ffffff) | 0xf5000000);
        background.setStroke(Math.max(1, Dimens.dp(context, 1)), (ArsoundTheme.palette(context, "special") & 0x00ffffff) | 0x80000000);
        background.setCornerRadius(Dimens.dp(context, 18));
        card.setBackground(background);

        TextView title = new TextView(context);
        title.setText(title(kind));
        title.setTextColor(ArsoundTheme.palette(context, "special"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(title);

        TextView body = new TextView(context);
        body.setText(body(kind, count));
        body.setTextColor(ArsoundTheme.palette(context, "primary"));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        body.setLineSpacing(0, 1.12f);
        body.setPadding(0, Dimens.dp(context, 6), 0, 0);
        card.addView(body);

        TextView hint = new TextView(context);
        hint.setText(text("Нажмите, чтобы закрыть", "Tap to close"));
        hint.setTextColor(ArsoundTheme.palette(context, "secondary"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        hint.setPadding(0, Dimens.dp(context, 8), 0, 0);
        card.addView(hint);
        return card;
    }

    static String title(Kind kind) {
        return kind == Kind.BLOCKED
                ? text("Лайки сейчас не сохраняются", "Likes are not being kept right now")
                : text("Много лайков за сутки", "A lot of likes in a day");
    }

    static String body(Kind kind, int count) {
        if (kind == Kind.BLOCKED) {
            return text("SoundCloud временно запретил этому аккаунту лайки: их было слишком много за короткое время. "
                            + "Это не поломка приложения и не страшно: запрет снимается сам, обычно ровно через сутки. "
                            + "Пока лучше не лайкать, лайки в это время не сохраняются.",
                    "SoundCloud has barred this account from liking for a while: there were too many likes in a short "
                            + "time. It is not a fault of the app and nothing to worry about: the bar lifts by itself, "
                            + "usually after exactly a day. Better not to like until then; likes are not kept in the meantime.");
        }
        return text("За последние сутки вы поставили " + count + " лайков. SoundCloud ограничивает их число "
                        + "(точное он не называет, где-то около сотни), а после этого перестаёт принимать новые, и они пропадают. "
                        + "Лучше сделать паузу. Если это всё же случится, не беспокойтесь: запрет обычно длится ровно сутки "
                        + "и снимается сам.",
                "In the last day you gave " + count + " likes. SoundCloud limits their number (it does not say how many, "
                        + "somewhere around a hundred), and after that it stops taking new ones, and they vanish. A pause is "
                        + "better. If it happens anyway, do not worry: the bar usually lasts exactly a day and lifts by itself.");
    }
}
