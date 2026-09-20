package app.revanced.extension.soundcloud.debug;

import android.view.View;

import app.revanced.extension.shared.Logger;

/**
 * Records what the player page is asked to show. Used to catch the rare case of the collapsed
 * player bar staying empty: the bar is drawn, but the track, the buttons and the titles are missing.
 * <p>
 * Nothing here changes behaviour. The lines only reach the file log while the log setting is on.
 */
@SuppressWarnings("unused")
public final class PlayerBarLog {
    private PlayerBarLog() {
    }

    /** Injection point. Called when the player page is filled with a track. */
    public static void logBind(Object view, Object item) {
        Logger.printDebug(() -> "Player page filled: item " + describe(item) + ", page " + describe(view));
    }

    /** Injection point. Called when the player page is emptied or moved to another queue item. */
    public static void logReset(Object view, Object queueItem) {
        Logger.printDebug(() -> "Player page reset: queue item " + describe(queueItem)
                + ", page " + describe(view));
    }

    /** Injection point. Called when the page is told which playback state to show. */
    public static void logPlayState(Object view, Object playState) {
        Logger.printDebug(() -> "Player page state: " + describe(playState) + ", page " + describe(view));
    }

    private static String describe(Object value) {
        if (value == null) return "none";
        if (value instanceof View) {
            View view = (View) value;
            return view.getClass().getSimpleName() + "(visible=" + (view.getVisibility() == View.VISIBLE)
                    + ", width=" + view.getWidth() + ", height=" + view.getHeight()
                    + ", children=" + (view instanceof android.view.ViewGroup
                    ? ((android.view.ViewGroup) view).getChildCount() : 0) + ")";
        }
        return value.getClass().getSimpleName();
    }
}
