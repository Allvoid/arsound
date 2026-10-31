package app.revanced.extension.soundcloud.shared;

import androidx.compose.runtime.MutableState;
import androidx.compose.runtime.SnapshotMutableIntStateImpl;

import app.revanced.extension.shared.Logger;

/**
 * A number that Compose screens follow. A screen that read it while it was composed ({@link #follow}) is composed
 * again when it changes ({@link #changed}). Without it a value that the app's own code gives to a composable (a
 * setting, a state of the downloads) is read once, and the screen keeps it until it is made again.
 */
public final class ComposeFollow {
    private final MutableState<Integer> version = create();
    private int count;

    private static MutableState<Integer> create() {
        try {
            return new SnapshotMutableIntStateImpl(0);
        } catch (Throwable ex) {
            Logger.printInfo(() -> "Compose screens are not told of changes: " + ex);
            return null;
        }
    }

    /** Called while a composable reads the value: inside a composition it makes the screen follow; elsewhere it does nothing. */
    public void follow() {
        MutableState<Integer> state = version;
        if (state != null) state.getValue();
    }

    /** The value changed: the screens that follow it are composed again. */
    public synchronized void changed() {
        MutableState<Integer> state = version;
        if (state == null) return;
        try {
            state.setValue(++count);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not tell the Compose screens of a change", ex);
        }
    }
}
