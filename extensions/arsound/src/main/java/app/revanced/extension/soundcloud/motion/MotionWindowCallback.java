package app.revanced.extension.soundcloud.motion;

import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;
import java.util.function.Consumer;

import app.revanced.extension.shared.Logger;

/**
 * The callback of a screen's window with one more job: it lets {@link MotionBlurRun} see every touch before the screen
 * does. Everything else goes straight to the callback it wraps.
 */
public final class MotionWindowCallback implements Window.Callback {
    private final Window.Callback inner;
    private final Consumer<MotionEvent> onTouch;

    /** @param onTouch Sees every touch of the screen before the screen does. */
    public MotionWindowCallback(Window.Callback inner, Consumer<MotionEvent> onTouch) {
        this.inner = inner;
        this.onTouch = onTouch;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        try {
            onTouch.accept(event);
        } catch (Throwable ex) {
            // The touch is the screen's: a failing blur must never take it away.
            Logger.printException(() -> "A touch watcher failed on a touch", ex);
        }
        return inner.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return inner.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        return inner.dispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        return inner.dispatchTrackballEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return inner.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        return inner.dispatchPopulateAccessibilityEvent(event);
    }

    @Override
    public View onCreatePanelView(int featureId) {
        return inner.onCreatePanelView(featureId);
    }

    @Override
    public boolean onCreatePanelMenu(int featureId, Menu menu) {
        return inner.onCreatePanelMenu(featureId, menu);
    }

    @Override
    public boolean onPreparePanel(int featureId, View view, Menu menu) {
        return inner.onPreparePanel(featureId, view, menu);
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        return inner.onMenuOpened(featureId, menu);
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        return inner.onMenuItemSelected(featureId, item);
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
        inner.onWindowAttributesChanged(attrs);
    }

    @Override
    public void onContentChanged() {
        inner.onContentChanged();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        inner.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onAttachedToWindow() {
        inner.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        inner.onDetachedFromWindow();
    }

    @Override
    public void onPanelClosed(int featureId, Menu menu) {
        inner.onPanelClosed(featureId, menu);
    }

    @Override
    public boolean onSearchRequested() {
        return inner.onSearchRequested();
    }

    @Override
    public boolean onSearchRequested(SearchEvent searchEvent) {
        return inner.onSearchRequested(searchEvent);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
        return inner.onWindowStartingActionMode(callback);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
        return inner.onWindowStartingActionMode(callback, type);
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
        inner.onActionModeStarted(mode);
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        inner.onActionModeFinished(mode);
    }

    @Override
    public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, Menu menu, int deviceId) {
        inner.onProvideKeyboardShortcuts(data, menu, deviceId);
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        inner.onPointerCaptureChanged(hasCapture);
    }
}
