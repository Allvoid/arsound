package app.revanced.extension.soundcloud.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

import app.revanced.extension.soundcloud.shared.Dimens;

/**
 * A round swatch of one colour for the colour picker. The view is a square of at least 48 dp to be easy to hit, the
 * disc inside it is smaller; a ring around the disc marks the picked colour. Made and laid out by {@link SwatchGrid}.
 */
final class ThemeSwatch extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String hex;
    private final int colour;
    private final int ringColour;
    private boolean picked;

    /**
     * @param hex        The colour as #RRGGBB.
     * @param label      Read out by accessibility services, with the colour.
     * @param ringColour The colour of the ring of the picked swatch.
     */
    ThemeSwatch(Context context, String hex, String label, int ringColour) {
        super(context);
        this.hex = hex;
        this.colour = Color.parseColor(hex) | 0xFF000000;
        this.ringColour = ringColour;
        setContentDescription(label + " " + hex);
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x33808080), null, new ColorDrawable(Color.WHITE)));
        setClickable(true);
    }

    String hex() {
        return hex;
    }

    /** Draws the ring around the disc, or takes it away. */
    void setPicked(boolean picked) {
        if (this.picked == picked) return;
        this.picked = picked;
        setSelected(picked);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float disc = Math.min(getWidth(), getHeight()) / 2f - Dimens.dpExact(getContext(), picked ? 8 : 6);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colour);
        canvas.drawCircle(cx, cy, disc, paint);
        // A faint edge, so black on a dark sheet and white on a light one still show as discs.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Dimens.dpExact(getContext(), 1)));
        paint.setColor(0x44808080);
        canvas.drawCircle(cx, cy, disc, paint);
        if (picked) {
            paint.setStrokeWidth(Dimens.dpExact(getContext(), 2.5f));
            paint.setColor(ringColour);
            canvas.drawCircle(cx, cy, disc + Dimens.dpExact(getContext(), 4), paint);
        }
    }
}
