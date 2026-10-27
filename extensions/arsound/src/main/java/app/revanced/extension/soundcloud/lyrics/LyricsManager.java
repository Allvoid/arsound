package app.revanced.extension.soundcloud.lyrics;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * What the lyrics of all player pages share: whether they are on, which track is being looked up, how far the player
 * is expanded. A player has up to three pages (the previous, the current and the next track), each with its own
 * {@link LyricsLayer}; only the current one shows words, and one lookup at a time goes out.
 * <p>
 * Everything here runs on the main thread, except {@link #onClockChanged}, which hands over to it.
 */
final class LyricsManager {
    private static final Set<LyricsLayer> LAYERS = Collections.newSetFromMap(new WeakHashMap<LyricsLayer, Boolean>());
    private static final int FALLBACK_ACCENT = 0xFFFF5500;

    private static TrackInfo loading;
    private static LyricsRepository.Request loadingRequest;
    /** How far the player is expanded, 0 to 1, as the last slide told; it starts collapsed. */
    private static float expand;
    private static boolean expandKnown;

    private LyricsManager() {
    }

    /** The master switch in the settings: off removes the button and every lookup. */
    static boolean isEnabled() {
        return Settings.isLyricsEnabled();
    }

    /** The button's state. */
    static boolean isVisible() {
        return Settings.isLyricsVisible();
    }

    static void toggle() {
        boolean visible = !isVisible();
        Settings.putBoolean(Settings.LYRICS_VISIBLE, visible);
        if (!visible) cancelLoading();
        for (LyricsLayer layer : layers()) {
            try {
                layer.onToggleChanged();
            } catch (Throwable ex) {
                Logger.printException(() -> "Lyrics: a page could not follow the button", ex);
            }
        }
    }

    static void register(LyricsLayer layer) {
        LAYERS.add(layer);
    }

    static void unregister(LyricsLayer layer) {
        LAYERS.remove(layer);
    }

    private static List<LyricsLayer> layers() {
        return new ArrayList<>(LAYERS);
    }

    static void setExpand(float fraction) {
        expand = fraction;
        expandKnown = true;
    }

    static float expand() {
        return expand;
    }

    /** False until the player has slid once: before that, a page works out for itself whether it is on screen. */
    static boolean isExpandKnown() {
        return expandKnown;
    }

    /** From the clock, which runs on whatever thread the player reports from. */
    static void onClockChanged() {
        Utils.runOnMainThread(new Runnable() {
            @Override
            public void run() {
                for (LyricsLayer layer : layers()) {
                    try {
                        layer.onClockChanged();
                    } catch (Throwable ex) {
                        Logger.printException(() -> "Lyrics: a page could not follow the player", ex);
                    }
                }
            }
        });
    }

    /** Asks for the lyrics of a track; every page showing it gets the answer through {@code onResult}. */
    static void load(final TrackInfo info) {
        LyricsResult known = LyricsRepository.peek(info);
        if (known != null) {
            dispatch(info, known);
            return;
        }
        if (loading != null && loading.sameTrack(info)) return;
        cancelLoading();
        loading = info;
        loadingRequest = LyricsRepository.request(info, new LyricsRepository.Callback() {
            @Override
            public void onResult(LyricsResult result) {
                if (loading == info) {
                    loading = null;
                    loadingRequest = null;
                }
                dispatch(info, result);
            }
        });
    }

    static void cancelLoading() {
        if (loadingRequest != null) loadingRequest.cancel();
        loading = null;
        loadingRequest = null;
    }

    private static void dispatch(TrackInfo info, LyricsResult result) {
        for (LyricsLayer layer : layers()) {
            try {
                layer.onResult(info, result);
            } catch (Throwable ex) {
                Logger.printException(() -> "Lyrics: a page could not show the lyrics", ex);
            }
        }
    }

    /** The accent of the chosen theme's dark look: the player is dark whatever the phone's mode. */
    static int accent(Context context) {
        int[] colors = ArsoundTheme.darkColors(context);
        return colors == null ? FALLBACK_ACCENT : colors[1];
    }

    /** The surface of the chosen theme's dark look. */
    static int surface(Context context) {
        int[] colors = ArsoundTheme.darkColors(context);
        return colors == null ? 0xFF000000 : colors[0];
    }
}
