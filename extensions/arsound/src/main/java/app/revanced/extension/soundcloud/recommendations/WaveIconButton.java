package app.revanced.extension.soundcloud.recommendations;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;

/**
 * A round icon button of the "My Wave" tab drawn from the mockup's own shapes: the settings gear (three rings, the
 * outer one dashed), the download (a circle with an arrow down) and the "⋮" (three dots). Under the finger it dips a
 * little and a round highlight, a circle inside the zone (never an oval that runs to the edges), fades in; the pointer
 * of a mouse or a stylus lights the same circle faintly; the keyboard and TalkBack get a ring. The download shows the
 * progress of what it started as an arc round the circle, then a tick.
 */
final class WaveIconButton extends View {
    enum Kind {GEAR, DOWNLOAD, MORE}

    private final Kind kind;
    private final int ink;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF box = new RectF();
    private final int zoneWidth, zoneHeight, iconSize;

    private float highlight;
    private boolean hovered, pressed;
    private ValueAnimator animator;
    /** The progress of what the download started, 0 to 1; negative when nothing runs. */
    private float progress = -1f;
    /** The tick after a finished download, shown until it is faded out. */
    private float done;
    private ValueAnimator doneAnimator;

    /**
     * @param zoneWidth  Width of the touch zone in dp.
     * @param zoneHeight Height of the touch zone in dp.
     * @param iconSize   Size of the picture in dp.
     */
    WaveIconButton(Context context, Kind kind, int ink, int zoneWidth, int zoneHeight, int iconSize) {
        super(context);
        this.kind = kind;
        this.ink = ink;
        this.zoneWidth = Dimens.dp(context, zoneWidth);
        this.zoneHeight = Dimens.dp(context, zoneHeight);
        this.iconSize = Dimens.dp(context, iconSize);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(ink);
        fill.setColor(ink);
        glow.setColor(ink);
        setClickable(true);
        setFocusable(true);
        setOnHoverListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER) hovered = true;
            else if (action == MotionEvent.ACTION_HOVER_EXIT) hovered = false;
            else return false;
            lightTo(target());
            return false;
        });
    }

    /** The download's progress: 0 to 1 while it runs; {@code -1} when it does not. A run that ends shows a tick. */
    void setProgress(float value) {
        if (kind != Kind.DOWNLOAD || value == progress) return;
        boolean ended = progress >= 0f && value < 0f;
        progress = value;
        if (ended) showDone();
        invalidate();
    }

    private void showDone() {
        if (doneAnimator != null) doneAnimator.cancel();
        doneAnimator = ValueAnimator.ofFloat(1f, 0f);
        doneAnimator.setStartDelay(900);
        doneAnimator.setDuration(500);
        doneAnimator.addUpdateListener(a -> {
            done = (float) a.getAnimatedValue();
            invalidate();
        });
        done = 1f;
        doneAnimator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(zoneWidth, zoneHeight);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start();
                lightTo(target());
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
                lightTo(target());
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        return super.performClick();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        lightTo(target());
    }

    private float target() {
        if (pressed) return 0.2f;
        if (isFocused()) return 0.16f;
        return hovered ? 0.1f : 0f;
    }

    private void lightTo(float value) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(highlight, value);
        animator.setDuration(pressed ? 70 : 180);
        animator.addUpdateListener(a -> {
            highlight = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        // The highlight is a circle inside the zone, with a margin, so it never runs to the edges.
        if (highlight > 0.001f) {
            glow.setAlpha(Math.round(255 * highlight));
            canvas.drawCircle(cx, cy, Math.min(w, h) / 2f - Dimens.dpExact(getContext(), 2), glow);
        }
        float unit = iconSize / 24f;
        canvas.save();
        canvas.translate(cx - iconSize / 2f, cy - iconSize / 2f);
        canvas.scale(unit, unit);
        switch (kind) {
            case GEAR:
                drawGear(canvas);
                break;
            case DOWNLOAD:
                drawDownload(canvas);
                break;
            default:
                drawMore(canvas);
                break;
        }
        canvas.restore();
        if (isFocused()) {
            stroke.setStrokeWidth(Dimens.dpExact(getContext(), 1.5f));
            stroke.setPathEffect(null);
            stroke.setAlpha(0xB0);
            canvas.drawCircle(cx, cy, Math.min(w, h) / 2f - Dimens.dpExact(getContext(), 1), stroke);
        }
    }

    private void drawGear(Canvas canvas) {
        stroke.setAlpha(255);
        stroke.setStrokeWidth(2.4f);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        stroke.setPathEffect(new DashPathEffect(new float[]{3f, 3.75f}, 0f));
        canvas.drawCircle(12, 12, 8.6f, stroke);
        stroke.setPathEffect(null);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(1.8f);
        canvas.drawCircle(12, 12, 6.3f, stroke);
        canvas.drawCircle(12, 12, 2.5f, stroke);
    }

    private void drawDownload(Canvas canvas) {
        stroke.setPathEffect(null);
        stroke.setAlpha(255);
        stroke.setStrokeWidth(1.7f);
        boolean running = progress >= 0f;
        // While it runs the ring is faint and an arc of the colour goes round it with the progress.
        stroke.setAlpha(running ? 0x55 : 0xFF);
        canvas.drawCircle(12, 12, 9.5f, stroke);
        if (running) {
            stroke.setAlpha(0xFF);
            stroke.setStrokeWidth(2.2f);
            box.set(2.5f, 2.5f, 21.5f, 21.5f);
            canvas.drawArc(box, -90f, 360f * Math.max(0.04f, progress), false, stroke);
            stroke.setStrokeWidth(1.7f);
        }
        if (done > 0f && !running) {
            stroke.setAlpha(Math.round(255 * done));
            path.reset();
            path.moveTo(7.5f, 12.5f);
            path.lineTo(10.8f, 15.8f);
            path.lineTo(16.8f, 8.6f);
            canvas.drawPath(path, stroke);
            stroke.setAlpha(255);
            return;
        }
        stroke.setAlpha(255);
        path.reset();
        path.moveTo(12, 7.5f);
        path.lineTo(12, 16f);
        path.moveTo(8.5f, 12.5f);
        path.lineTo(12, 16f);
        path.lineTo(15.5f, 12.5f);
        canvas.drawPath(path, stroke);
    }

    private void drawMore(Canvas canvas) {
        fill.setStyle(Paint.Style.FILL);
        canvas.drawCircle(12, 5.5f, 1.9f, fill);
        canvas.drawCircle(12, 12, 1.9f, fill);
        canvas.drawCircle(12, 18.5f, 1.9f, fill);
    }

    /** A colour a little off the main one, for text that sits quieter. */
    static int muted(int color) {
        return Colors.withAlpha(color, 0xB8);
    }
}
