package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;

/**
 * A source chip of the "My Wave" tab, in the mockup's shape: 44 dp high, corners of 14 dp. Turned on it has a border
 * of the accent, the accent at 30 % flooding it from the point the finger touched, a filled dot of 8 dp before the
 * name and the name in the text colour, semibold; turned off, a faint border, a ring in place of the dot and a quiet
 * name. The dot fills and empties as the chip changes. The chip gives a short tap of the vibration, dips under the
 * finger and springs back; a chip that must stay on shakes its head instead; hover and keyboard focus light it a little.
 */
final class WaveChip extends View {
    /** The label's typefaces, made once: asked for in every frame of the chip's animation before. */
    private static final Typeface LABEL_OFF = Typeface.create(Typeface.DEFAULT, 500, false);
    private static final Typeface LABEL_ON = Typeface.create(Typeface.DEFAULT, 600, false);

    /** Answers a tap: whether the chip may change; it then shows the new state itself. */
    interface Toggle {
        boolean onToggle(boolean nowOn);
    }

    private final String label;
    private final int accent, onAccent, plain, textColor;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path clip = new Path();
    private final Path tickPath = new Path();
    private final int padH, height, dotSpace;
    private final float corner;
    private float glow;

    private boolean on;
    /** How far the accent has flooded the chip, from 0 (off) to 1 (on). */
    private float progress;
    private float touchX, touchY;
    private ValueAnimator flood;
    private Toggle toggle;

    WaveChip(Context context, String label, boolean on, int accent, int plain, int textColor) {
        super(context);
        this.label = label;
        this.on = on;
        this.progress = on ? 1f : 0f;
        this.accent = accent;
        this.onAccent = Colors.contrast(accent);
        this.plain = plain;
        this.textColor = textColor;
        padH = Dimens.dp(context, 12);
        height = Dimens.dp(context, 44);
        dotSpace = Dimens.dp(context, 8 + 8);
        corner = Dimens.dpExact(context, 14);
        text.setTextSize(Dimens.dpExact(context, 14.5f));
        text.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeWidth(Dimens.dpExact(context, 1.5f));
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(Dimens.dpExact(context, 1.2f));
        setClickable(true);
        setFocusable(true);
        updateDescription();
    }

    void setToggle(Toggle toggle) {
        this.toggle = toggle;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int wanted = Math.round(padH * 2 + dotSpace + text.measureText(label));
        setMeasuredDimension(resolveSize(wanted, widthMeasureSpec), height);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchX = event.getX();
                touchY = event.getY();
                animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).setInterpolator(new DecelerateInterpolator()).start();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(3f)).start();
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        boolean wanted = !on;
        if (toggle != null && !toggle.onToggle(wanted)) {
            refuse();
            return true;
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        on = wanted;
        updateDescription();
        if (flood != null) flood.cancel();
        flood = ValueAnimator.ofFloat(progress, on ? 1f : 0f);
        flood.setDuration(on ? 340 : 260);
        flood.setInterpolator(new DecelerateInterpolator(1.6f));
        flood.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        flood.start();
        return true;
    }

    /** A short shake: this chip cannot be turned off. */
    private void refuse() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        float step = Dimens.dpExact(getContext(), 5);
        ValueAnimator shake = ValueAnimator.ofFloat(0f, 1f);
        shake.setDuration(320);
        shake.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            setTranslationX((float) (Math.sin(t * Math.PI * 4) * step * (1 - t)));
        });
        shake.start();
    }

    private void updateDescription() {
        setContentDescription(label + (on ? text(", включён", ", on") : text(", выключен", ", off")));
        setSelected(on);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        bounds.set(0, 0, w, h);
        // A quiet tint of the text colour over the page; lit a little by the pointer or the keyboard focus.
        fill.setColor(Colors.withAlpha(textColor, Math.round(0x0A + 0x14 * glow)));
        canvas.drawRoundRect(bounds, corner, corner, fill);

        if (progress > 0f) {
            canvas.save();
            clip.reset();
            clip.addRoundRect(bounds, corner, corner, Path.Direction.CW);
            canvas.clipPath(clip);
            // Far enough from the touch point to reach every corner.
            float reach = (float) Math.hypot(Math.max(touchX, w - touchX), Math.max(touchY, h - touchY));
            fill.setColor(Colors.withAlpha(accent, 0x4D));
            canvas.drawCircle(touchX, touchY, reach * progress, fill);
            canvas.restore();
        }
        bounds.inset(outline.getStrokeWidth() / 2f, outline.getStrokeWidth() / 2f);
        outline.setColor(Colors.blend(Colors.withAlpha(textColor, Math.round(0x29 + 0x30 * glow)), accent, progress));
        canvas.drawRoundRect(bounds, corner, corner, outline);

        int ink = Colors.blend(Colors.withAlpha(textColor, 0xB8), textColor, progress);
        text.setColor(ink);
        text.setTypeface(progress > 0.5f ? LABEL_ON : LABEL_OFF);
        float textWidth = text.measureText(label);
        float dot = Dimens.dpExact(getContext(), 4);
        float start = (w - textWidth - dotSpace) / 2f;
        float cx = start + dot, cy = h / 2f;
        // The dot: a ring while off; the ring shrinks into a filled dot as the chip comes on.
        tick.setColor(ink);
        tick.setStyle(Paint.Style.STROKE);
        tick.setAlpha(Math.round(255 * (1f - progress)));
        canvas.drawCircle(cx, cy, dot - tick.getStrokeWidth() / 2f, tick);
        fill.setColor(ink);
        fill.setAlpha(Math.round(255 * progress));
        canvas.drawCircle(cx, cy, dot * (0.4f + 0.6f * progress), fill);
        Paint.FontMetrics metrics = text.getFontMetrics();
        canvas.drawText(label, start + dotSpace, cy - (metrics.ascent + metrics.descent) / 2f, text);
    }

    @Override
    public void setHovered(boolean hovered) {
        super.setHovered(hovered);
        light();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        light();
    }

    private void light() {
        float target = isFocused() ? 1f : isHovered() ? 0.6f : 0f;
        ValueAnimator animation = ValueAnimator.ofFloat(glow, target);
        animation.setDuration(160);
        animation.addUpdateListener(a -> {
            glow = (float) a.getAnimatedValue();
            invalidate();
        });
        animation.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (touchX == 0 && touchY == 0) {
            touchX = w / 2f;
            touchY = h / 2f;
        }
    }
}
