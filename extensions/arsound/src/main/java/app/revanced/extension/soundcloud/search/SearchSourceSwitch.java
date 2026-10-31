package app.revanced.extension.soundcloud.search;

import android.app.Activity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.player.PlayerSheet;

/**
 * A switch under the search field of the Search tab: SoundCloud (the usual search) or Arsound.
 * Arsound searches YouTube Music for tracks that SoundCloud does not let download. A result can be
 * listened to with a tap and downloaded with the button on the right; downloads go to the imported music.
 * <p>
 * This class finds the search screen of the main activity and puts a {@link SearchScreen} on it; the screen,
 * {@link PreviewPlayer} and {@link DownloadQueue} do the rest.
 */
public final class SearchSourceSwitch {
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    /** The decor that {@link #layoutListener} was added to, so the listener is removed from the right one. */
    private static WeakReference<View> listenedDecor = new WeakReference<>(null);
    /** The search screen that has the switch, so the usual layout pass finds that it is done without looking for it. */
    private static WeakReference<LinearLayout> switched = new WeakReference<>(null);
    private static int coordinatorId = -1;

    private SearchSourceSwitch() {
    }

    private static int id(String name) {
        return Utils.getResourceIdentifier(ResourceType.ID, name);
    }

    /** The main activity while it is on screen, or null. */
    static Activity resumedActivity() {
        return activity.get();
    }

    /** Called when an activity comes to the screen. The search screen lives in the main activity. */
    public static void onActivityResumed(Activity resumed) {
        if (!resumed.getClass().getName().endsWith(".MainActivity")) return;
        activity = new WeakReference<>(resumed);
        View decor = resumed.getWindow().getDecorView();
        // A recreated activity has a new decor: the old listener comes off the decor it was added to.
        View previous = listenedDecor.get();
        if (previous != null && layoutListener != null) {
            previous.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        }
        // The decor is held weakly: this static listener must not keep a destroyed activity alive.
        WeakReference<View> weakDecor = new WeakReference<>(decor);
        layoutListener = () -> {
            View current = weakDecor.get();
            // The slide of the player lays the screen out on every frame, and no search screen opens in the middle of it.
            if (current != null && !PlayerSheet.isBusy()) attach(current);
        };
        listenedDecor = weakDecor;
        decor.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        attach(decor);
    }

    public static void onActivityPaused(Activity paused) {
        if (activity.get() == paused) PreviewPlayer.stop();
    }

    /** Adds the switch to the search screen if it is shown and has no switch yet. */
    private static void attach(View decor) {
        try {
            // The screen found last is kept whatever the "Smooth player collapse" setting: the lookups by name ran on every
            // layout of the slide with the setting off.
            LinearLayout known = switched.get();
            if (known != null && known.isAttachedToWindow() && known.findViewWithTag(SourceToggle.TAG) != null) return;
            if (coordinatorId < 0) coordinatorId = id("search_coordinator");
            View found = decor.findViewById(coordinatorId);
            if (!(found instanceof LinearLayout)) return;
            LinearLayout coordinator = (LinearLayout) found;
            if (coordinator.findViewWithTag(SourceToggle.TAG) != null) {
                switched = new WeakReference<>(coordinator);
                return;
            }
            View container = coordinator.findViewById(id("search_container"));
            View edit = coordinator.findViewById(id("search_edit_text"));
            if (container == null || !(edit instanceof EditText)) return;
            SearchScreen.makeCurrent(new SearchScreen(coordinator, container, (EditText) edit));
            switched = new WeakReference<>(coordinator);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add the search switch", ex);
        }
    }
}
