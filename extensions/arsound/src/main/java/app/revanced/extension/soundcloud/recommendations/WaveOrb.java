package app.revanced.extension.soundcloud.recommendations;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;

/**
 * The big picture of the "My Wave" tab: soft rings in the theme's accent that flow round a plain round play button. The rings drift
 * slowly while the wave rests and swell and move faster while it plays. They move only while the tab is on screen
 * ({@link #setRunning}); a tap anywhere on the picture is a tap on the button.
 */
final class WaveOrb extends View {
    /** How many points make one ring. */
    private static final int POINTS = 120;
    private static final int RINGS = 4;

    private final int accent;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint button = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int maxHeight;

    private boolean playing;
    private boolean running;
    /** Where the rings are in their movement; it moves on with the time the picture was drawn. */
    private float phase;
    private long lastFrame;
    /** How far the rings are swollen, from 0 (resting) to 1 (playing); it follows {@link #playing} smoothly. */
    private float swell;

    /**
     * @param ink  The button: the theme's main text colour, white on a dark look and black on a light one.
     * @param page The play mark cut into the button: the colour of the page under it.
     */
    WaveOrb(Context context, int accent, int ink, int page) {
        super(context);
        this.accent = accent;
        maxHeight = Dimens.dp(context, 340);
        ring.setStyle(Paint.Style.FILL);
        button.setColor(ink);
        icon.setColor(page);
        // Soft corners on the triangle and the bars, as SoundCloud's own play marks.
        icon.setPathEffect(new CornerPathEffect(Dimens.dpExact(context, 3)));
        setClickable(true);
        setFocusable(true);
    }

    void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        invalidate();
    }

    /** Lets the rings move (the tab is on screen) or stops them. */
    void setRunning(boolean running) {
        if (this.running == running) return;
        this.running = running;
        lastFrame = 0;
        if (running) postInvalidateOnAnimation();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, Math.min(width, maxHeight));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float radius = Math.min(width, height) / 2f;
        glow.setShader(new RadialGradient(width / 2f, height / 2f, Math.max(1f, radius),
                new int[]{Colors.withAlpha(accent, 0x55), Colors.withAlpha(accent, 0x00)}, null, Shader.TileMode.CLAMP));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
        else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            animate().scaleX(1f).scaleY(1f).setDuration(160).start();
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        if (running && lastFrame != 0) {
            float seconds = Math.min(0.1f, (now - lastFrame) / 1000f);
            phase += seconds * (playing ? 1.1f : 0.35f);
            swell += (playing ? 1 : -1) * seconds * 1.5f;
            swell = Math.max(0f, Math.min(1f, swell));
        } else if (!running) {
            swell = playing ? 1f : 0f;
        }
        lastFrame = running ? now : 0;

        float width = getWidth(), height = getHeight();
        float cx = width / 2f, cy = height / 2f;
        float base = Math.min(width, height) * 0.27f;
        canvas.drawCircle(cx, cy, Math.min(width, height) / 2f, glow);

        for (int i = RINGS - 1; i >= 0; i--) {
            float radius = base * (1f + 0.17f * i) * (1f + 0.05f * swell);
            float amplitude = (0.045f + 0.05f * swell) * (1f - 0.12f * i);
            int lobes = 3 + i;
            float turn = phase * (i % 2 == 0 ? 1f : -1f) * (0.7f + 0.25f * i);
            path.reset();
            for (int p = 0; p <= POINTS; p++) {
                double angle = 2 * Math.PI * p / POINTS;
                double r = radius * (1 + amplitude * Math.sin(lobes * angle + turn)
                        + amplitude * 0.5 * Math.sin((lobes + 2) * angle - turn * 1.3));
                float x = (float) (cx + r * Math.cos(angle)), y = (float) (cy + r * Math.sin(angle));
                if (p == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.close();
            ring.setColor(Colors.withAlpha(accent, Math.max(0x14, 0x4C - i * 0x10 + Math.round(0x14 * swell))));
            canvas.drawPath(path, ring);
        }

        float knob = base * 0.46f;
        canvas.drawCircle(cx, cy, knob, button);
        drawIcon(canvas, cx, cy, knob * 0.36f);

        if (running) postInvalidateOnAnimation();
    }

    /** The play triangle while the wave rests, two pause bars while it plays. */
    private void drawIcon(Canvas canvas, float cx, float cy, float size) {
        if (playing) {
            float bar = size * 0.42f, gap = size * 0.28f;
            canvas.drawRoundRect(cx - gap - bar, cy - size, cx - gap, cy + size, bar / 2f, bar / 2f, icon);
            canvas.drawRoundRect(cx + gap, cy - size, cx + gap + bar, cy + size, bar / 2f, bar / 2f, icon);
            return;
        }
        // The triangle's middle is a little right of the centre, so it looks centred.
        float shift = size * 0.18f;
        path.reset();
        path.moveTo(cx - size * 0.75f + shift, cy - size);
        path.lineTo(cx + size + shift, cy);
        path.lineTo(cx - size * 0.75f + shift, cy + size);
        path.close();
        canvas.drawPath(path, icon);
    }
}
