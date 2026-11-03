package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Presses a media key for the player, as the headphones' button would: the media session that plays (Arsound's)
 * gets it. Used where Arsound's own buttons must pause or resume SoundCloud's player.
 */
public final class MediaKeys {
    private MediaKeys() {
    }

    public static void play(Context context) {
        press(context, KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    public static void pause(Context context) {
        press(context, KeyEvent.KEYCODE_MEDIA_PAUSE);
    }

    private static void press(Context context, int key) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0));
    }
}
