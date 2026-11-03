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
 * A source chip of the "My Wave" tab. Turned on, the accent floods it from the point the finger touched and a tick
 * slides in before the name; turned off, the colour drains back into that point. The chip gives a short tap of the
 * vibration, dips under the finger and springs back; a chip that must stay on shakes its head instead.
 */
final class WaveChip extends View {
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
    private final int padH, height, tickSpace;

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
        padH = Dimens.dp(context, 16);
        height = Dimens.dp(context, 40);
        tickSpace = Dimens.dp(context, 20);
        text.setTextSize(Dimens.dpExact(context, 15));
        text.setTypeface(Typeface.DEFAULT_BOLD);
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeWidth(Dimens.dpExact(context, 2.2f));
        tick.setStrokeCap(Paint.Cap.ROUND);
        tick.setStrokeJoin(Paint.Join.ROUND);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(Dimens.dpExact(context, 1));
        outline.setColor(Colors.withAlpha(accent, 0x66));
        setClickable(true);
        setFocusable(true);
        updateDescription();
    }

    void setToggle(Toggle toggle) {
        this.toggle = toggle;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = Math.round(padH * 2 + tickSpace + text.measureText(label));
        setMeasuredDimension(width, height);
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
        float w = getWidth(), h = getHeight(), radius = h / 2f;
        bounds.set(0, 0, w, h);
        fill.setColor(plain);
        canvas.drawRoundRect(bounds, radius, radius, fill);

        if (progress > 0f) {
            canvas.save();
            clip.reset();
            clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            // Far enough from the touch point to reach every corner.
            float reach = (float) Math.hypot(Math.max(touchX, w - touchX), Math.max(touchY, h - touchY));
            fill.setColor(accent);
            canvas.drawCircle(touchX, touchY, reach * progress, fill);
            canvas.restore();
        }
        bounds.inset(outline.getStrokeWidth() / 2f, outline.getStrokeWidth() / 2f);
        outline.setAlpha(Math.round(0x66 * (1 - progress)));
        canvas.drawRoundRect(bounds, radius, radius, outline);

        int ink = Colors.blend(textColor, onAccent, progress);
        // The name moves right as the tick comes in, so the two stay centred together.
        float textWidth = text.measureText(label);
        float start = (w - textWidth - tickSpace * progress) / 2f;
        if (progress > 0.05f) {
            tick.setColor(Colors.withAlpha(ink, Math.round(255 * Math.min(1f, progress * 1.4f))));
            float size = Dimens.dpExact(getContext(), 7);
            float cx = start + size, cy = h / 2f;
            tickPath.reset();
            tickPath.moveTo(cx - size * 0.7f, cy);
            tickPath.lineTo(cx - size * 0.15f, cy + size * 0.55f);
            tickPath.lineTo(cx + size * 0.8f, cy - size * 0.5f);
            canvas.save();
            canvas.scale(progress, progress, cx, cy);
            canvas.drawPath(tickPath, tick);
            canvas.restore();
        }
        text.setColor(ink);
        Paint.FontMetrics metrics = text.getFontMetrics();
        canvas.drawText(label, start + tickSpace * progress, h / 2f - (metrics.ascent + metrics.descent) / 2f, text);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (touchX == 0 && touchY == 0) {
            touchX = w / 2f;
            touchY = h / 2f;
        }
    }
}
