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

    static void start(Context context, long at) {
        stopAt = at;
        handler.removeCallbacks(onTimerFired);
        handler.postDelayed(onTimerFired, Math.max(0, at - System.currentTimeMillis()));
        Logger.printInfo(() -> "Sleep timer: music stops at " + TimerFormat.clock(at));
        Toast.makeText(context, text("Музыка остановится в ", "Music stops at ") + TimerFormat.clock(at)
                + " (" + TimerFormat.duration(TimerFormat.minutesUntil(at, System.currentTimeMillis())) + ")",
                Toast.LENGTH_SHORT).show();
    }

    static void cancel(Context context) {
        stopAt = 0;
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
