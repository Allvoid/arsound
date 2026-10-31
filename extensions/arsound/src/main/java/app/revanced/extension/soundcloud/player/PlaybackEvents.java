package app.revanced.extension.soundcloud.player;

import app.revanced.extension.shared.Logger;

/**
 * Injection points of SoundCloud's player that more than one feature follows: an item starts, the player reports its
 * position. They are handed on to the smooth transition ({@link Crossfade}) and the loading of the next track
 * ({@link NextTrackPreload}).
 */
@SuppressWarnings("unused")
public final class PlaybackEvents {
    /** The urn of the item that started last; a resume of it is not a new track. */
    private static volatile String playingUrn;

    private PlaybackEvents() {
    }

    /** Injection point: start of {@code BaseExoPlayer.f(PlaybackItem)}, an item starts or resumes. Player thread. */
    public static void onPlay(Object exoPlayer, Object item) {
        String urn;
        try {
            urn = String.valueOf(item.getClass().getMethod("h").invoke(item));
        } catch (Exception ex) {
            Logger.printException(() -> "Playback events: the item has no urn", ex);
            return;
        }
        boolean newTrack = !urn.equals(playingUrn);
        playingUrn = urn;
        Crossfade.onPlay(exoPlayer, newTrack);
        if (newTrack) {
            NextTrackPreload.onTrackChanged();
            SlowedPlayback.onNewItem(exoPlayer, urn);
        }
    }

    /** Injection point: start of {@code LocalPlayback.i(ProgressChangeEvent)}, about twice a second while playing. */
    public static void onProgress(long position, long duration) {
        Crossfade.onProgress(position, duration);
        NextTrackPreload.onProgress(position, duration);
    }
}
