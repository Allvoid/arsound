package app.revanced.extension.soundcloud.theme;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.AppResources;

/**
 * The theme's glow on the home screen lies over the whole screen (the foreground of the theme's home layout, so it is
 * even on the bar and on the list under it), and so it also lay over the user's picture in the top bar: a photo looked
 * tinted with the accent (bot ticket #67). This wraps that foreground and leaves out the circle of the picture, so the
 * picture is above the glow; the rest of the glow stays as it was.
 */
final class GlowAroundAvatar extends Drawable implements Drawable.Callback {
    private final Drawable glow;
    private final ViewGroup screen;
    private final Path hole = new Path();
    private final int[] screenAt = new int[2];
    private final int[] buttonAt = new int[2];
    private View avatar;
    /** The description of the profile button, read once: the screen's language does not change under a screen. */
    private String label;
    /** Whether the tree is to be searched again: at the start, and after the screen was laid out. */
    private boolean searchDue = true;
    /** Any layout of the screen asks for a new search, so a button that appeared later is found. */
    private final View.OnLayoutChangeListener relaidOut = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
            searchDue = true;

    private GlowAroundAvatar(Drawable glow, ViewGroup screen) {
        this.glow = glow;
        this.screen = screen;
        glow.setCallback(this);
        screen.addOnLayoutChangeListener(relaidOut);
    }

    /** Wraps the screen's foreground once; nothing if it has none. */
    static void apply(ViewGroup screen) {
        try {
            Drawable foreground = screen.getForeground();
            if (foreground == null || foreground instanceof GlowAroundAvatar) return;
            GlowAroundAvatar wrapped = new GlowAroundAvatar(foreground, screen);
            wrapped.setBounds(foreground.getBounds());
            screen.setForeground(wrapped);
            // Setting the foreground takes the callback off the glow: it is given back, or an animated glow stops drawing.
            foreground.setCallback(wrapped);
        } catch (Exception ex) {
            Logger.printException(() -> "Home glow: could not keep it off the picture", ex);
        }
    }

    /**
     * The profile button of the screen's top bar, found once it is there. The tree is walked after a layout of the
     * screen only, not on every frame: a redraw finds the same answer.
     */
    private View avatar() {
        if (avatar != null && avatar.isAttachedToWindow()) return avatar;
        avatar = null;
        if (!searchDue) return null;
        searchDue = false;
        if (label == null) {
            int id = AppResources.identifier(screen.getContext(), "accessibility_open_profile", "string");
            if (id == 0) return null;
            label = screen.getContext().getString(id);
        }
        ArrayList<View> found = new ArrayList<>();
        screen.findViewsWithText(found, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        if (!found.isEmpty()) avatar = found.get(0);
        return avatar;
    }

    @Override
    public void draw(Canvas canvas) {
        View button = avatar();
        if (button == null || button.getVisibility() != View.VISIBLE || button.getWidth() == 0) {
            glow.draw(canvas);
            return;
        }
        screen.getLocationInWindow(screenAt);
        button.getLocationInWindow(buttonAt);
        // Where the picture is drawn in the button: its drawable's bounds through the image matrix, inside the padding.
        android.graphics.RectF picture = pictureBounds(button);
        float cx = buttonAt[0] - screenAt[0] + picture.centerX();
        float cy = buttonAt[1] - screenAt[1] + picture.centerY();
        float radius = Math.min(picture.width(), picture.height()) / 2f;
        hole.reset();
        hole.addCircle(cx, cy, radius, Path.Direction.CW);
        int saved = canvas.save();
        if (Build.VERSION.SDK_INT >= 26) canvas.clipOutPath(hole);
        else canvas.clipPath(hole, Region.Op.DIFFERENCE);
        glow.draw(canvas);
        canvas.restoreToCount(saved);
    }

    private final android.graphics.RectF pictureRect = new android.graphics.RectF();

    private android.graphics.RectF pictureBounds(View button) {
        Drawable picture = button instanceof android.widget.ImageView ? ((android.widget.ImageView) button).getDrawable() : null;
        float width = button.getWidth(), height = button.getHeight();
        if (picture != null && !picture.getBounds().isEmpty()) {
            pictureRect.set(picture.getBounds());
            ((android.widget.ImageView) button).getImageMatrix().mapRect(pictureRect);
            pictureRect.offset(button.getPaddingLeft(), button.getPaddingTop());
            // A drawable stretched over the whole button (a ripple around the picture) tells nothing: its own size does.
            if (!pictureRect.isEmpty() && pictureRect.width() < width * 0.9f) return pictureRect;
        }
        if (picture != null && picture.getIntrinsicWidth() > 0 && picture.getIntrinsicWidth() < width * 0.9f) {
            float w = picture.getIntrinsicWidth(), h = picture.getIntrinsicHeight() > 0 ? picture.getIntrinsicHeight() : w;
            pictureRect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f);
            return pictureRect;
        }
        // Not known: a circle well inside the button.
        float size = Math.min(button.getWidth(), button.getHeight()) * 0.56f;
        pictureRect.set((button.getWidth() - size) / 2f, (button.getHeight() - size) / 2f,
                (button.getWidth() + size) / 2f, (button.getHeight() + size) / 2f);
        return pictureRect;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        glow.setBounds(bounds);
    }

    @Override
    public void setAlpha(int alpha) {
        glow.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        glow.setColorFilter(filter);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        invalidateSelf();
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        scheduleSelf(what, when);
    }

    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
        unscheduleSelf(what);
    }
}
