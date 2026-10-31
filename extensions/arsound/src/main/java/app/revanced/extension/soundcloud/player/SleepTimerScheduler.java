package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.Toast;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/** The sleep timer itself: when the music stops, and the pause when that time comes. Lives in the app process. */
final class SleepTimerScheduler {
    private static final Handler handler = new Handler(Looper.getMainLooper());
    /** When the music stops, in {@link System#currentTimeMillis()}; 0 when the timer is off. */
    private static long stopAt;

    private static final Runnable onTimerFired = () -> {
        long left = stopAt - System.currentTimeMillis();
        if (stopAt == 0) return;
        // The handler counts uptime, which stands still in deep sleep: check the clock and wait the rest.
        if (left > 1000) {
            handler.postDelayed(SleepTimerScheduler.onTimerFired, left);
            return;
        }
        stopAt = 0;
        Context context = Utils.getContext();
        if (context != null) save(context, 0);
        pauseMusic();
    };

    private SleepTimerScheduler() {
    }

    static boolean isActive() {
        return stopAt > System.currentTimeMillis();
    }

    /** When the music stops; meaningful while {@link #isActive}. */
    static long stopAt() {
        return stopAt;
    }

    /** The time the music stops, kept on the phone: the timer outlives the process (a firmware that ends it in the background). */
    private static final String PREFERENCES_NAME = "arsound_sleep_timer";
    private static final String STOP_AT = "stop_at";

    private static void save(Context context, long at) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putLong(STOP_AT, at).apply();
    }

    /**
     * Called when the app starts: a timer set before the process was ended goes on, if its time has not passed. A time
     * that passed while the app was gone is dropped: the music did not play then.
     */
    static void restore(Context context) {
        long at = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getLong(STOP_AT, 0);
        if (at <= System.currentTimeMillis()) {
            if (at != 0) save(context, 0);
            return;
        }
        stopAt = at;
        handler.removeCallbacks(onTimerFired);
        handler.postDelayed(onTimerFired, at - System.currentTimeMillis());
        Logger.printInfo(() -> "Sleep timer: restored, music stops at " + TimerFormat.clock(at));
    }

    static void start(Context context, long at) {
        stopAt = at;
        save(context, at);
        handler.removeCallbacks(onTimerFired);
        handler.postDelayed(onTimerFired, Math.max(0, at - System.currentTimeMillis()));
        Logger.printInfo(() -> "Sleep timer: music stops at " + TimerFormat.clock(at));
        Toast.makeText(context, text("Музыка остановится в ", "Music stops at ") + TimerFormat.clock(at)
                + " (" + TimerFormat.duration(TimerFormat.minutesUntil(at, System.currentTimeMillis())) + ")",
                Toast.LENGTH_SHORT).show();
    }

    static void cancel(Context context) {
        stopAt = 0;
        save(context, 0);
        handler.removeCallbacks(onTimerFired);
        Logger.printInfo(() -> "Sleep timer: off");
        Toast.makeText(context, text("Таймер сна выключен", "Sleep timer is off"), Toast.LENGTH_SHORT).show();
    }

    /** Pauses SoundCloud the way a headset button does. */
    private static void pauseMusic() {
        try {
            Context context = Utils.getContext();
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            long now = SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            Logger.printInfo(() -> "Sleep timer: music paused");
        } catch (Exception ex) {
            Logger.printException(() -> "Sleep timer could not pause the music", ex);
        }
    }
}
