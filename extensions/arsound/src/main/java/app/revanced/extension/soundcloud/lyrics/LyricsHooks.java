package app.revanced.extension.soundcloud.lyrics;

import android.view.View;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.player.PlayerArtworkLayers;
import app.revanced.extension.soundcloud.player.SlowedButton;

/**
 * Where the patched player calls into the lyrics: when a player page is filled with a track, and when the player
 * slides open or shut. Both run on the main thread, often (every state of the track, every frame of a slide), and a
 * throw here would take the player down with it, so they do little and catch everything.
 */
@SuppressWarnings("unused")
public final class LyricsHooks {
    private static final PlayerStateReader STATE_READER = new PlayerStateReader();

    private LyricsHooks() {
    }

    /**
     * Injection point. The start of the player's method that fills a page with a track.
     *
     * @param view The page, or the view the player wraps it in.
     * @param item The page's state ({@code PlayerTrackState} for a track, something else for an ad).
     */
    public static void onBind(Object view, Object item) {
        try {
            if (!(view instanceof View)) return;
            View page = LyricsLayer.pageOf((View) view);
            if (page == null) return;
            // The slowed button is the player's, not the lyrics': it is there whatever the lyrics are set to.
            SlowedButton.attach(page);
            LyricsLayer layer = LyricsLayer.forPage(page);
            if (layer == null) {
                LyricsLayer.hideButton(page);
                return;
            }
            if (!STATE_READER.read(item, layer)) layer.bind(null, null, null, 0, false);
        } catch (Throwable ex) {
            Logger.printException(() -> "Lyrics: could not bind a player page", ex);
        }
    }

    /**
     * Injection point. The start of the player's method that sets how far a page is open.
     *
     * @param fraction 0 for the mini player, 1 for the full screen.
     */
    public static void onSlide(View view, float fraction) {
        try {
            // A page beside the visible one sits the slide out, as it does for SoundCloud's own work: the call is put
            // before the guard that skips it. It gets the final look at the ends, 0 and 1, which always come through.
            if (PlayerArtworkLayers.skipSlide(view, fraction)) return;
            View page = LyricsLayer.pageOf(view);
            if (page == null) return;
            LyricsLayer layer = LyricsLayer.forPage(page);
            if (layer != null) layer.onSlide(fraction);
        } catch (Throwable ex) {
            Logger.printException(() -> "Lyrics: could not follow the player sliding", ex);
        }
    }
}
