package app.revanced.extension.soundcloud.theme.effect;

import android.graphics.Canvas;
import android.graphics.Rect;

/** One animated background: what it is made of and how it draws a frame. The drawable around it only keeps the time. */
public interface EffectScene {
    /**
     * The size of the drawable is known or changed: makes what depends on it (gradients, the ice). Called before the
     * first {@link #draw}, with bounds that are not empty.
     */
    void layout(Rect bounds);

    /**
     * Draws the frame at this time. The canvas is clipped to the drawable and moved so that (0, 0) is its top-left corner.
     *
     * @param time Seconds since the effect was made.
     */
    void draw(Canvas canvas, float time, float w, float h);
}
