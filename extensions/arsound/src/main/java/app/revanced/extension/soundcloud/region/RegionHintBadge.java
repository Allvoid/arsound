package app.revanced.extension.soundcloud.region;

import static app.revanced.extension.soundcloud.shared.Dimens.dp;
import static app.revanced.extension.soundcloud.shared.Dimens.dpExact;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.revanced.extension.soundcloud.shared.HelpBadge;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The "?" that stands next to "Not available in your country" and wiggles every few seconds as if asking to be
 * tapped. A tap opens {@link RegionHintDialog}. The view is a wrapper: the gap before the mark and some room around
 * it make the target bigger than the small mark, and the wiggle can move out of the mark's own bounds.
 */
final class RegionHintBadge extends FrameLayout {
    /** The size of the mark: that of SoundCloud's own label icons. */
    private static final int MARK_DP = 16;
    private static final int GAP_DP = 6;

    /** Every badge that is on screen, to hide them at once; main thread only. */
    private static final Set<RegionHintBadge> LIVE = Collections.newSetFromMap(new WeakHashMap<>());

    private final TextView mark;
    private final Runnable wiggle = this::wiggle;
    private ValueAnimator animator;
    private boolean hidden;

    RegionHintBadge(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(dp(context, GAP_DP), 0, dp(context, GAP_DP), 0);
        int accent = ArsoundTheme.palette(context, "special");
        mark = HelpBadge.create(context, accent, RegionHintText.badgeDescription(Lang.russian()));
        // The tap of the mark is its own; this one is for the room around it.
        mark.setOnClickListener(v -> RegionHintDialog.show(context));
        setOnClickListener(v -> RegionHintDialog.show(context));
        addView(mark, new LayoutParams(dp(context, MARK_DP), dp(context, MARK_DP), Gravity.CENTER_VERTICAL));
    }

    /** Hides every badge on screen. A badge shown later is not made at all while the hint is off. */
    static void hideAll() {
        for (RegionHintBadge badge : new ArrayList<>(LIVE)) badge.hide();
    }

    private void hide() {
        hidden = true;
        stopWiggle();
        setVisibility(GONE);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // A host that lays its child out by the child's last size (Compose's) takes no room once hidden.
        if (hidden) {
            setMeasuredDimension(0, 0);
            return;
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        LIVE.add(this);
        scheduleWiggle();
    }

    @Override
    protected void onDetachedFromWindow() {
        LIVE.remove(this);
        stopWiggle();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) scheduleWiggle();
        else stopWiggle();
    }

    private boolean canWiggle() {
        return !hidden && isAttachedToWindow() && getWindowVisibility() == VISIBLE && animationsOn();
    }

    /** The system switch "Remove animations" sets the animation scale to 0. */
    private boolean animationsOn() {
        try {
            return Settings.Global.getFloat(getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** All badges wiggle at the same beats, so a list of them moves together instead of twitching at random. */
    private void scheduleWiggle() {
        removeCallbacks(wiggle);
        if (!canWiggle()) return;
        postDelayed(wiggle, ShakeCurve.delayUntilNext(SystemClock.uptimeMillis()));
    }

    private void wiggle() {
        if (!canWiggle()) return;
        if (!isShown()) {
            scheduleWiggle();
            return;
        }
        float shiftPx = dpExact(getContext(), 1f);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ShakeCurve.DURATION_MS);
        animator.addUpdateListener(a -> {
            float progress = (float) a.getAnimatedValue();
            mark.setRotation(ShakeCurve.degrees(progress));
            mark.setTranslationX(ShakeCurve.shiftDp(progress) * shiftPx);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mark.setRotation(0f);
                mark.setTranslationX(0f);
                if (!cancelled) scheduleWiggle();
            }
        });
        animator.start();
    }

    private void stopWiggle() {
        removeCallbacks(wiggle);
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        mark.setRotation(0f);
        mark.setTranslationX(0f);
    }

    /** The badge as a plain view, for a layout that does not know it. */
    static View create(Context context) {
        return new RegionHintBadge(context);
    }
}
