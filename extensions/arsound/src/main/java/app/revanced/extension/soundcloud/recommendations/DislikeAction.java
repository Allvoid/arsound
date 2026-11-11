package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.media.AudioManager;
import android.view.KeyEvent;
import android.widget.Toast;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.player.PlaybackEvents;

/**
 * The broken-heart button of the player, in whichever place it stands (the full player, the media notification and the
 * lock screen, the 2 x 2 widget): "not for me" for the track that plays, then the next track. The track is written to the
 * list of {@link TrackDislikes}, so it is not recommended again, and the player goes on to the next one.
 */
public final class DislikeAction {
    private DislikeAction() {
    }

    /** Whether the button is wanted: the "Not for me" feature is on. */
    public static boolean isAvailable() {
        return TrackDislikes.isEnabled();
    }

    /** Marks the playing track as "not for me" and moves to the next one. Any thread. */
    public static void run(Context context) {
        String urn = PlaybackEvents.playingUrn();
        if (!TrackUrn.isTrack(urn)) {
            toast(context, text("Сейчас играет не трек SoundCloud", "No SoundCloud track is playing"));
            return;
        }
        try {
            TrackDislikes.add(TrackUrn.normalize(urn));
            toast(context, text("Больше не рекомендуем этот трек", "This track will not be recommended any more"));
            next(context);
        } catch (Exception ex) {
            Logger.printException(() -> "Not for me: could not mark the playing track", ex);
        }
    }

    /** The media key "next": it reaches the player the way the headset button does, whichever screen is on. */
    private static void next(Context context) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT));
    }

    private static void toast(Context context, String message) {
        if (context != null) Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }
}
