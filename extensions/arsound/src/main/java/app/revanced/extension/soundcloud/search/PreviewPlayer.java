package app.revanced.extension.soundcloud.search;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;

import app.arsound.shaded.newpipe.extractor.stream.AudioStream;
import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Toasts;

/**
 * Plays one result of the Arsound search so the user can check it is the right track: a tap starts it, another tap
 * stops it. SoundCloud's own player pauses while the preview has the audio focus.
 */
final class PreviewPlayer {
    private static MediaPlayer player;
    private static String playingUrl;
    private static boolean preparing;
    /** Changes with every tap and every stop, so a stream lookup of an earlier tap cannot start a second player. */
    private static volatile int token;
    private static AudioFocusRequest focusRequest;

    private PreviewPlayer() {
    }

    static boolean isPlaying(String url) {
        return url.equals(playingUrl);
    }

    /** True from the tap until the player is ready to start. */
    static boolean isPreparing() {
        return preparing;
    }

    /**
     * A tap on a result: starts its preview, or stops it if it is the one playing.
     *
     * @param context The application context.
     */
    static void toggle(Context context, String url) {
        if (url.equals(playingUrl)) {
            stop();
            return;
        }
        stop();
        int current = ++token;
        playingUrl = url;
        preparing = true;
        SearchScreen.refreshRows();
        SearchThreads.NETWORK.execute(() -> {
            // Stopped or tapped again while waiting in the queue: no need to look the stream up.
            if (current != token) return;
            try {
                AudioStream stream = OtherSource.bestAudio(url);
                OtherSource.PartSource source = new OtherSource.PartSource(stream.getContent());
                SearchThreads.MAIN.post(() -> start(context, current, source));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not get the stream", ex);
                SearchThreads.MAIN.post(() -> {
                    if (current != token) return;
                    stop();
                    String general = text("Не получилось включить трек.", "Could not play the track.");
                    SearchFailure.of(ex).report(context, general, general);
                });
            }
        });
    }

    private static void start(Context context, int current, OtherSource.PartSource source) {
        if (current != token) return;
        MediaPlayer created = null;
        try {
            created = new MediaPlayer();
            created.setAudioAttributes(audioAttributes());
            created.setDataSource(source);
            created.setOnPreparedListener(prepared -> {
                if (player != prepared) return;
                preparing = false;
                // SoundCloud pauses its own playback when it loses the audio focus.
                requestFocus(context);
                prepared.start();
                SearchScreen.refreshRows();
            });
            created.setOnCompletionListener(finished -> stop());
            created.setOnErrorListener((failed, what, extra) -> {
                Logger.printInfo(() -> "Preview error " + what + "/" + extra);
                stop();
                Toasts.showLong(context, text("Не получилось включить трек.", "Could not play the track."));
                return true;
            });
            player = created;
            created.prepareAsync();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not start the preview", ex);
            // A player that did not reach the field yet is not released by stop.
            if (created != null && created != player) {
                try {
                    created.release();
                } catch (Exception ignored) {
                }
            }
            source.close();
            stop();
        }
    }

    static void stop() {
        // Drops the stream lookup and the player start of the preview that is being stopped.
        token++;
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        Activity current = SearchSourceSwitch.resumedActivity();
        if (focusRequest != null && current != null) {
            current.getSystemService(AudioManager.class).abandonAudioFocusRequest(focusRequest);
        }
        focusRequest = null;
        if (playingUrl == null) return;
        playingUrl = null;
        preparing = false;
        SearchScreen.refreshRows();
    }

    private static AudioAttributes audioAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    private static void requestFocus(Context context) {
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes())
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS) stop();
                })
                .build();
        context.getSystemService(AudioManager.class).requestAudioFocus(focusRequest);
    }
}
