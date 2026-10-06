package app.revanced.extension.soundcloud.network;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.AboveBottomBar;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * A small round icon of a crossed-out globe at the bottom right of the main screen, above the player: no network,
 * or SoundCloud switched off on a Russian IP. It is drawn in the colours of the chosen theme.
 * <p>
 * When it appears, it unfolds for a moment into a pill with a short explanation, then folds back into the icon.
 * A tap checks the network and the IP again (the globe turns while it checks); a long press shows the explanation.
 * Once the app is online again, the globe loses its line, says "Connected" and goes away.
 */
public final class NetworkBanner {
    private static final String TAG = "arsound_network_banner";
    private static final long REFRESH_MS = 5_000;
    private static final long MESSAGE_MS = 3_500;
    private static final long CONNECTED_MS = 1_800;

    private enum State {ONLINE, OFFLINE, BLOCKED}

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static State shown = State.ONLINE;
    private static boolean callbackRegistered;
    private static boolean checking;

    private NetworkBanner() {
    }

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    /** Called when an activity comes to the screen. Only the main screen gets the icon. */
    public static void onActivityResumed(Activity resumed) {
        if (!resumed.getClass().getName().endsWith(".MainActivity")) return;
        if (!Settings.isNetworkBannerEnabled()) {
            Holder holder = find(resumed);
            if (holder != null) holder.root.setVisibility(View.GONE);
            shown = State.ONLINE;
            return;
        }
        activity = new WeakReference<>(resumed);
        registerCallback(resumed);
        handler.removeCallbacks(PERIODIC);
        handler.post(PERIODIC);
    }

    public static void onActivityPaused(Activity paused) {
        if (activity.get() == paused) handler.removeCallbacks(PERIODIC);
    }

    private static final Runnable PERIODIC = new Runnable() {
        @Override
        public void run() {
            update(false);
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private static void registerCallback(Context context) {
        if (callbackRegistered) return;
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    handler.postDelayed(() -> update(false), 500);
                }

                @Override
                public void onLost(Network network) {
                    handler.post(() -> update(false));
                }
            });
            callbackRegistered = true;
        } catch (Exception ex) {
            Logger.printException(() -> "Network icon: could not watch the network", ex);
        }
    }

    private static State currentState(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return State.OFFLINE;
        }
        if (Settings.isRegionGuardEnabled() && "RU".equals(RegionGuard.lastCountry())) return State.BLOCKED;
        return State.ONLINE;
    }

    private static String explanation(State state) {
        return state == State.OFFLINE
                ? text("Нет сети — играют скачанные треки", "No network — downloaded tracks play")
                : text("Российский IP — SoundCloud отключён", "Russian IP — SoundCloud is off");
    }

    /** @param answer The user asked for a check: the result is spelled out even if nothing changed. */
    private static void update(boolean answer) {
        Activity current = activity.get();
        if (current == null || current.isFinishing() || checking) return;
        try {
            State state = currentState(current);
            if (state == shown && !answer) return;
            State previous = shown;
            shown = state;
            if (state == State.ONLINE) {
                Holder holder = find(current);
                if (holder == null || holder.root.getVisibility() != View.VISIBLE) return;
                if (previous != State.ONLINE || answer) {
                    holder.setState(false, ArsoundTheme.palette(current, "special"));
                    holder.say(text("Подключено", "Connected"), CONNECTED_MS, () -> holder.hide());
                } else {
                    holder.hide();
                }
                return;
            }
            Holder holder = holder(current);
            if (holder == null) return;
            holder.setState(true, state == State.BLOCKED
                    ? ArsoundTheme.palette(current, "special") : ArsoundTheme.palette(current, "secondary"));
            holder.show();
            String message = explanation(state);
            if (answer && previous == state) message = state == State.BLOCKED
                    ? text("Всё ещё российский IP", "Still a Russian IP") : text("Сети всё ещё нет", "Still no network");
            holder.say(message, MESSAGE_MS, null);
        } catch (Exception ex) {
            Logger.printException(() -> "Network icon: could not update", ex);
        }
    }

    private static void recheck(Holder holder) {
        if (checking) return;
        checking = true;
        holder.spin(true);
        holder.say(text("Проверяю…", "Checking…"), 0, null);
        RegionGuard.recheck(() -> {
            checking = false;
            holder.spin(false);
            update(true);
        });
    }

    // region The icon

    private static Holder find(Activity current) {
        ViewGroup content = current.findViewById(android.R.id.content);
        View existing = content == null ? null : content.findViewWithTag(TAG);
        return existing == null ? null : (Holder) existing.getTag(TAG.hashCode());
    }

    private static Holder holder(Activity current) {
        Holder existing = find(current);
        if (existing != null) return existing;
        ViewGroup content = current.findViewById(android.R.id.content);
        if (content == null) return null;
        Holder holder = new Holder(current);
        content.addView(holder.root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END));
        AboveBottomBar.attach(current, content, holder.root, holder.pill);
        return holder;
    }

    /** The round icon that can unfold into a pill with a line of text. */
    private static final class Holder {
        final Context context;
        final FrameLayout root;
        final LinearLayout pill;
        final ImageView icon;
        final TextView label;
        final GlobeDrawable globe;
        private ValueAnimator unfold;
        private Runnable fold;

        Holder(Activity current) {
            context = current;
            int shadow = dp(8);
            root = new FrameLayout(current);
            root.setTag(TAG);
            root.setTag(TAG.hashCode(), this);
            root.setClipChildren(false);
            root.setClipToPadding(false);
            root.setPadding(shadow, shadow, dp(12), shadow);

            pill = new LinearLayout(current);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            GradientDrawable background = new GradientDrawable();
            background.setColor((ArsoundTheme.palette(current, "dialog") & 0x00ffffff) | 0xf2000000);
            background.setStroke(Math.max(1, dp(1)), (ArsoundTheme.palette(current, "special") & 0x00ffffff) | 0x59000000);
            background.setCornerRadius(dp(100));
            pill.setBackground(background);
            pill.setElevation(dp(4));
            pill.setMinimumHeight(dp(36));
            pill.setVisibility(View.GONE);
            pill.setContentDescription(text("Статус сети", "Network status"));

            globe = new GlobeDrawable(dp(1.6f));
            icon = new ImageView(current);
            icon.setImageDrawable(globe);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(20), dp(20));
            iconParams.leftMargin = iconParams.rightMargin = dp(8);
            pill.addView(icon, iconParams);

            label = new TextView(current);
            label.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            label.setTextColor(ArsoundTheme.palette(current, "primary"));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(dp(12), 0, 0, 0);
            label.setVisibility(View.GONE);
            pill.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT));

            pill.setOnClickListener(v -> recheck(this));
            pill.setOnLongClickListener(v -> {
                if (shown != State.ONLINE) say(explanation(shown), MESSAGE_MS, null);
                return true;
            });
            root.addView(pill, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        }

        void setState(boolean crossed, int color) {
            globe.set(crossed, color);
        }

        void show() {
            root.setVisibility(View.VISIBLE);
            if (pill.getVisibility() == View.VISIBLE && pill.getTag() == null) return;
            pill.setTag(null);
            pill.animate().cancel();
            if (pill.getVisibility() != View.VISIBLE) {
                pill.setAlpha(0f);
                pill.setScaleX(.6f);
                pill.setScaleY(.6f);
                pill.setVisibility(View.VISIBLE);
            }
            pill.setPivotX(pill.getWidth() == 0 ? dp(18) : pill.getWidth() - dp(18));
            pill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(new OvershootInterpolator(1.6f)).withEndAction(null).start();
        }

        void hide() {
            if (pill.getVisibility() != View.VISIBLE) return;
            pill.setTag(Boolean.TRUE);
            foldNow();
            pill.animate().cancel();
            pill.animate().alpha(0f).scaleX(.6f).scaleY(.6f).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                        if (pill.getTag() != null) pill.setVisibility(View.GONE);
                    }).start();
        }

        /** Unfolds the pill with a message; it folds back after the given time, never if 0. */
        void say(String message, long visibleMs, Runnable after) {
            show();
            label.setText(message);
            if (fold != null) handler.removeCallbacks(fold);
            int screen = context.getResources().getDisplayMetrics().widthPixels;
            label.measure(View.MeasureSpec.makeMeasureSpec(screen - dp(120), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            animateLabel(label.getMeasuredWidth());
            if (visibleMs > 0) {
                fold = () -> {
                    foldNow();
                    if (after != null) handler.postDelayed(after, 250);
                };
                handler.postDelayed(fold, visibleMs);
            }
        }

        private void foldNow() {
            if (fold != null) handler.removeCallbacks(fold);
            animateLabel(0);
        }

        private void animateLabel(int width) {
            if (unfold != null) unfold.cancel();
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) label.getLayoutParams();
            int from = label.getVisibility() == View.VISIBLE ? label.getWidth() : 0;
            if (from == width) return;
            label.setVisibility(View.VISIBLE);
            params.width = from;
            label.setLayoutParams(params);
            unfold = ValueAnimator.ofInt(from, width);
            unfold.setDuration(280);
            unfold.setInterpolator(new DecelerateInterpolator(1.8f));
            unfold.addUpdateListener(a -> {
                params.width = (int) a.getAnimatedValue();
                label.setLayoutParams(params);
                label.setAlpha(width == 0 ? 1 - a.getAnimatedFraction() : a.getAnimatedFraction());
            });
            unfold.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (width == 0 && params.width == 0) label.setVisibility(View.GONE);
                }
            });
            unfold.start();
        }

        void spin(boolean on) {
            icon.animate().cancel();
            if (on) {
                ValueAnimator turn = ValueAnimator.ofFloat(0, 360);
                turn.setDuration(900);
                turn.setRepeatCount(ValueAnimator.INFINITE);
                turn.setInterpolator(new LinearInterpolator());
                turn.addUpdateListener(a -> icon.setRotation((float) a.getAnimatedValue()));
                icon.setTag(turn);
                turn.start();
            } else {
                if (icon.getTag() instanceof ValueAnimator) ((ValueAnimator) icon.getTag()).cancel();
                icon.setTag(null);
                icon.animate().rotation(icon.getRotation() > 180 ? 360 : 0).setDuration(200)
                        .withEndAction(() -> icon.setRotation(0)).start();
            }
        }

        private int dp(float value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }

    /** A line globe: the outline, a meridian and the equator, crossed out by a slash with a gap around it. */
    private static final class GlobeDrawable extends Drawable {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gap = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float crossed;
        private ValueAnimator slash;

        GlobeDrawable(float stroke) {
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(stroke);
            line.setStrokeCap(Paint.Cap.ROUND);
            gap.setStyle(Paint.Style.STROKE);
            gap.setStrokeWidth(stroke * 3);
            gap.setStrokeCap(Paint.Cap.ROUND);
            gap.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        }

        /** The slash draws itself in or out. */
        void set(boolean crossedOut, int color) {
            line.setColor(color);
            float target = crossedOut ? 1 : 0;
            if (slash != null) slash.cancel();
            if (crossed == target) {
                invalidateSelf();
                return;
            }
            slash = ValueAnimator.ofFloat(crossed, target);
            slash.setDuration(300);
            slash.addUpdateListener(a -> {
                crossed = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            slash.start();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float inset = line.getStrokeWidth();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float r = Math.min(b.width(), b.height()) / 2f - inset;
            // A separate layer, so the gap around the slash cuts the globe and not what is under the icon.
            int save = canvas.saveLayer(b.left, b.top, b.right, b.bottom, null);
            canvas.drawCircle(cx, cy, r, line);
            canvas.drawOval(cx - r * .42f, cy - r, cx + r * .42f, cy + r, line);
            canvas.drawLine(cx - r, cy, cx + r, cy, line);
            if (crossed > 0) {
                float x0 = cx - r * 1.05f, y0 = cy - r * 1.05f;
                float x1 = x0 + (r * 2.1f) * crossed, y1 = y0 + (r * 2.1f) * crossed;
                canvas.drawLine(x0, y0, x1, y1, gap);
                canvas.drawLine(x0, y0, x1, y1, line);
            }
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            line.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            line.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // endregion
}
