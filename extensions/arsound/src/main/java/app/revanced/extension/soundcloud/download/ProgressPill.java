package app.revanced.extension.soundcloud.download;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * A small round counter at the bottom of the main screen while a playlist is checked or downloaded:
 * "14 of 48", nothing more.
 * <p>
 * The counter stands above the bottom navigation, or above the collapsed player when there is one.
 * When the player appears or goes away, the counter slides to its new place. While the player is
 * open on the whole screen, the counter is hidden.
 */
public final class ProgressPill {
    private static final String TAG = "arsound_progress_pill";
    private static final long HIDE_DELAY_MS = 1_500;
    private static final long MOVE_MS = 250;

    private ProgressPill() {
    }

    /** Tracks checked so far and in all, while a check runs. Zero total means no check, below zero a list still loading. */
    private static int checked, checkTotal;
    /** Tracks of the current downloads: those not started yet, and all of them. */
    private static final Set<String> pending = new HashSet<>();
    private static final Set<String> batch = new HashSet<>();

    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static int shownDone = -1, shownTotal = -1;

    private static String text(int done, int total) {
        return "ru".equals(Locale.getDefault().getLanguage())
                ? done + " из " + total
                : done + " of " + total;
    }

    // region Counting

    /** Called when a playlist check is asked for, before its list of tracks has loaded. */
    static void onCheckLoading() {
        onCheckStarted(-1);
    }

    /** Called when the list of tracks to check has loaded. */
    static void onCheckStarted(int total) {
        Utils.runOnMainThread(() -> {
            checked = 0;
            checkTotal = total;
            refresh();
        });
    }

    static void onChecked(int done) {
        Utils.runOnMainThread(() -> {
            if (checkTotal <= 0) return;
            checked = Math.min(done, checkTotal);
            refresh();
        });
    }

    /** Called when the check ended, with or without a result. */
    static void onCheckFinished() {
        Utils.runOnMainThread(() -> {
            checkTotal = 0;
            refresh();
        });
    }

    /** Called before the downloads of a playlist are started. */
    static void onDownloadsQueued(Iterable<String> trackIds) {
        Utils.runOnMainThread(() -> {
            if (batch.isEmpty()) pending.clear();
            for (String id : trackIds) {
                if (batch.add(id)) pending.add(id);
            }
            refresh();
        });
    }

    /** Called when a download of the batch was started, or could not start at all. */
    static void onDownloadStarted(String trackId, boolean started) {
        Utils.runOnMainThread(() -> {
            pending.remove(trackId);
            // A track that did not start is left out of the count.
            if (!started) batch.remove(trackId);
            refresh();
        });
    }

    /** Called when the set of running downloads may have changed. */
    static void onDownloadsChanged() {
        Utils.runOnMainThread(ProgressPill::refresh);
    }

    private static int downloadedOfBatch() {
        int done = 0;
        for (String id : batch) {
            if (!pending.contains(id) && !DownloadProgress.isDownloading(id)) done++;
        }
        return done;
    }

    // endregion

    // region Screen

    public static void onActivityResumed(Activity resumed) {
        if (!resumed.getClass().getName().endsWith(".MainActivity")) return;
        activity = new WeakReference<>(resumed);
        shownDone = shownTotal = -1;
        refresh();
    }

    /** Draws the current numbers, or hides the counter when nothing is counted. */
    private static void refresh() {
        Activity current = activity.get();
        if (current == null || current.isFinishing()) return;

        int done, total;
        if (checkTotal != 0) {
            done = checked;
            total = checkTotal;
        } else if (!batch.isEmpty()) {
            done = downloadedOfBatch();
            total = batch.size();
        } else {
            hide(current);
            return;
        }
        if (done == shownDone && total == shownTotal) return;
        shownDone = done;
        shownTotal = total;

        try {
            TextView view = pill(current);
            if (view == null) return;
            view.setText(total < 0 ? "…" : text(done, total));
            show(view);
            if (checkTotal == 0 && done >= total) {
                // All downloads ended: the final numbers stay for a moment, then the counter goes.
                view.removeCallbacks(FINISH);
                view.postDelayed(FINISH, HIDE_DELAY_MS);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Progress counter: could not draw", ex);
        }
    }

    private static final Runnable FINISH = () -> {
        if (checkTotal != 0 || downloadedOfBatch() < batch.size()) return;
        batch.clear();
        pending.clear();
        refresh();
    };

    private static TextView pill(Activity current) {
        ViewGroup content = current.findViewById(android.R.id.content);
        if (content == null) return null;
        View existing = content.findViewWithTag(TAG);
        if (existing instanceof TextView) return (TextView) existing;

        TextView view = new TextView(current);
        view.setTag(TAG);
        view.setTextColor(0xffffffff);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER);
        int horizontal = dp(current, 16), vertical = dp(current, 8);
        view.setPadding(horizontal, vertical, horizontal, vertical);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xf2333333);
        background.setCornerRadius(dp(current, 100));
        view.setBackground(background);
        view.setElevation(dp(current, 6));
        view.setVisibility(View.GONE);

        // The holder moves with the player bar, the counter inside fades in and out on its own.
        FrameLayout holder = new FrameLayout(current);
        holder.setClipChildren(false);
        holder.setClipToPadding(false);
        int shadow = dp(current, 8);
        holder.setPadding(shadow, shadow, shadow, shadow);
        holder.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(holder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        Placement placement = new Placement(current, content, holder, view);
        content.getViewTreeObserver().addOnPreDrawListener(placement);
        holder.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                content.getViewTreeObserver().removeOnPreDrawListener(placement);
            }
        });
        return view;
    }

    private static void show(TextView view) {
        boolean leaving = view.getTag(TAG.hashCode()) != null;
        if (view.getVisibility() == View.VISIBLE && !leaving) return;
        view.setTag(TAG.hashCode(), null);
        if (view.getVisibility() != View.VISIBLE) {
            view.setAlpha(0f);
            view.setVisibility(View.VISIBLE);
        }
        view.animate().alpha(1f).setDuration(200).withEndAction(null).start();
    }

    private static void hide(Activity current) {
        shownDone = shownTotal = -1;
        ViewGroup content = current.findViewById(android.R.id.content);
        if (content == null) return;
        View view = content.findViewWithTag(TAG);
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        // Marks the counter as going away, so a new count shows it again.
        view.setTag(TAG.hashCode(), Boolean.TRUE);
        view.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (view.getTag(TAG.hashCode()) != null) view.setVisibility(View.GONE);
        }).start();
    }

    /**
     * Keeps the counter above the lowest bar on screen: the collapsed player, the bottom
     * navigation, or the screen edge. Runs before every frame, but only moves the counter when
     * the place has changed.
     */
    private static final class Placement implements ViewTreeObserver.OnPreDrawListener {
        private final Context context;
        private final ViewGroup content;
        private final View holder, pill;
        private final int playerId, navigationId;
        private final int[] location = new int[2];
        private float target = Float.NaN;
        private boolean playerOpen;

        Placement(Context context, ViewGroup content, View holder, View pill) {
            this.context = context;
            this.content = content;
            this.holder = holder;
            this.pill = pill;
            playerId = context.getResources().getIdentifier("player_track_pager", "id", context.getPackageName());
            navigationId = context.getResources().getIdentifier("navigation_control_view", "id", context.getPackageName());
        }

        @Override
        public boolean onPreDraw() {
            if (pill.getVisibility() != View.VISIBLE) {
                // Placed again from scratch next time, without sliding in from the old place.
                target = Float.NaN;
                return true;
            }
            content.getLocationOnScreen(location);
            int contentTop = location[1];
            int contentBottom = contentTop + content.getHeight();

            int barTop = contentBottom;
            int navigationTop = topOf(navigationId);
            if (navigationTop >= 0 && navigationTop < barTop) barTop = navigationTop;

            boolean open = false;
            int playerTop = topOf(playerId);
            if (playerTop >= 0 && playerTop < barTop) {
                // Higher than the middle of the screen means the player is opened, not collapsed.
                if (playerTop < contentTop + content.getHeight() / 2) open = true;
                else barTop = playerTop;
            }

            if (open != playerOpen) {
                playerOpen = open;
                holder.animate().alpha(open ? 0f : 1f).setDuration(150).start();
            }
            if (open) return true;

            // The holder has a margin for the shadow, which is taken off the gap.
            float wanted = -(contentBottom - barTop + dp(context, 12) - holder.getPaddingBottom());
            if (wanted == target) return true;
            boolean first = Float.isNaN(target);
            target = wanted;
            if (first) {
                holder.setTranslationY(wanted);
            } else {
                holder.animate().translationY(wanted).setDuration(MOVE_MS)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
            return true;
        }

        /** Top of a visible view on screen, or -1 when it is not shown. */
        private int topOf(int id) {
            if (id == 0) return -1;
            View view = content.getRootView().findViewById(id);
            if (view == null || !view.isShown() || view.getHeight() == 0) return -1;
            view.getLocationOnScreen(location);
            return location[1];
        }
    }

    // endregion

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
