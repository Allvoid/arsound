package app.revanced.extension.soundcloud.network;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * A small status pill at the top of the main screen: no network, SoundCloud switched off on a
 * Russian IP, or connected again (shown for two seconds). A tap checks the network and the IP again.
 */
public final class NetworkBanner {
    private static final String TAG = "arsound_network_banner";
    private static final long CONNECTED_VISIBLE_MS = 2_000;
    private static final long REFRESH_MS = 5_000;

    private enum State {ONLINE, OFFLINE, BLOCKED}

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static State shown = State.ONLINE;
    private static boolean callbackRegistered;

    private NetworkBanner() {
    }

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    /** Called when an activity comes to the screen. Only the main screen gets the banner. */
    public static void onActivityResumed(Activity resumed) {
        if (!Settings.isNetworkBannerEnabled() || !resumed.getClass().getName().endsWith(".MainActivity")) return;
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
            update();
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
                    handler.postDelayed(NetworkBanner::update, 500);
                }

                @Override
                public void onLost(Network network) {
                    handler.post(NetworkBanner::update);
                }
            });
            callbackRegistered = true;
        } catch (Exception ex) {
            Logger.printException(() -> "Network banner: could not watch the network", ex);
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

    private static void update() {
        Activity current = activity.get();
        if (current == null || current.isFinishing()) return;
        try {
            State state = currentState(current);
            if (state == shown) return;
            State previous = shown;
            shown = state;
            if (state == State.ONLINE) {
                if (previous != State.ONLINE) {
                    show(current, text("Подключено", "Connected"), 0xff2e7d32);
                    handler.postDelayed(() -> {
                        if (shown == State.ONLINE) hide(current);
                    }, CONNECTED_VISIBLE_MS);
                } else {
                    hide(current);
                }
            } else if (state == State.OFFLINE) {
                show(current, text("Нет сети — играют скачанные и импортированные треки",
                        "No network — downloaded and imported tracks still play"), 0xff616161);
            } else {
                show(current, text("Российский IP — SoundCloud отключён. Нажмите, чтобы проверить снова",
                        "Russian IP — SoundCloud is off. Tap to check again"), 0xffe65100);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Network banner: could not update", ex);
        }
    }

    private static TextView banner(Activity current) {
        ViewGroup content = current.findViewById(android.R.id.content);
        if (content == null) return null;
        View existing = content.findViewWithTag(TAG);
        if (existing instanceof TextView) return (TextView) existing;

        TextView view = new TextView(current);
        view.setTag(TAG);
        view.setTextColor(0xffffffff);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setGravity(Gravity.CENTER);
        int horizontal = dp(current, 14), vertical = dp(current, 7);
        view.setPadding(horizontal, vertical, horizontal, vertical);
        view.setElevation(dp(current, 6));
        view.setVisibility(View.GONE);
        view.setOnClickListener(v -> {
            view.setText(text("Проверяю сеть…", "Checking the network…"));
            RegionGuard.recheck(() -> {
                // Forces the banner to redraw even if the state did not change.
                shown = null;
                update();
            });
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = statusBarHeight(current) + dp(current, 64);
        params.leftMargin = params.rightMargin = dp(current, 16);
        content.addView(view, params);
        return view;
    }

    private static void show(Activity current, String message, int color) {
        TextView view = banner(current);
        if (view == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(current, 20));
        view.setBackground(background);
        view.setText(message);
        if (view.getVisibility() != View.VISIBLE) {
            view.setAlpha(0f);
            view.setVisibility(View.VISIBLE);
            view.animate().alpha(1f).setDuration(200).start();
        }
    }

    private static void hide(Activity current) {
        TextView view = banner(current);
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        view.animate().alpha(0f).setDuration(200).withEndAction(() -> view.setVisibility(View.GONE)).start();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int statusBarHeight(Context context) {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id == 0 ? dp(context, 24) : context.getResources().getDimensionPixelSize(id);
    }
}
