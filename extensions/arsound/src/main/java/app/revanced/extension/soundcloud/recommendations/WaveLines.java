package app.revanced.extension.soundcloud.recommendations;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import app.revanced.extension.soundcloud.player.AudioLevel;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ThemeEffect;

/**
 * The strip of the "My Wave" tab: three thin lines that swing with the music, and in the middle the play button, a
 * 56 dp ring with the play or pause mark. The lines answer to the loudness of the music
 * ({@link AudioLevel}, read from the player's sound chain; the player's volume when that is not running) through
 * {@link WaveLevel}. The lines run only while the tab is on screen ({@link #setRunning}); at rest they are drawn about
 * twenty times a second, not on every frame. In battery saving and with the system animations off they stand still.
 * Nothing is allocated while drawing. A tap on the button is the player's play or pause.
 */
final class WaveLines extends View {
    private static final float TAU = (float) (Math.PI * 2);
    private static final int LINES = 3;
    /** The mockup's strip is 360 wide; the lines are worked out in these units and scaled to the view. */
    private static final float DESIGN_WIDTH = 360f;
    private static final float[] K = {TAU / 360, TAU / 360, TAU / 180};
    private static final float[] SPEED = {1f, 0.7f, 1.5f};
    private static final float[] SHIFT = {0f, (float) Math.PI, 1f};
    private static final float[] SWING = {1f, 0.75f, 0.45f};
    private static final float[] OPACITY = {0.95f, 0.5f, 0.3f};
    private static final float[] WIDTH = {1.3f, 1.1f, 1f};
    /** The step along the strip, in design units. */
    private static final int STEP = 3;
    private static final long RESTING_FRAME_MS = 50;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path trianglePath = new Path();
    private final int accent;
    private final int ink;
    private final int mixWith;
    private final int height;
    private final AudioManager audio;
    private final Runnable frame = this::invalidate;

    private boolean playing;
    private boolean running;
    private boolean still;
    private float level;
    private float phase;
    private long lastFrame;
    /** How far the play mark has turned into the pause mark, 0 (play) to 1 (pause). */
    private float markProgress;
    private float press;
    private boolean touching;

    /**
     * @param accent The theme's accent; the lines are it, mixed 72 % with white.
     * @param ink    The ring and the mark of the button.
     * @param page   The colour of the page under the strip: on a light page the lines are mixed with the ink, not with
     *               white, so they do not fade into it.
     */
    WaveLines(Context context, int accent, int ink, int page) {
        super(context);
        this.accent = accent;
        this.ink = ink;
        this.mixWith = Colors.brightness(page) > 0.6 ? ink : 0xFFFFFFFF;
        height = Dimens.dp(context, 150);
        audio = context.getSystemService(AudioManager.class);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        ring.setStyle(Paint.Style.STROKE);
        mark.setStyle(Paint.Style.FILL);
        mark.setColor(ink);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setClickable(true);
        setFocusable(true);
    }

    void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        scheduleNext();
    }

    /** Lets the lines move (the tab is on screen) or stops them. */
    void setRunning(boolean running) {
        if (this.running == running) return;
        this.running = running;
        lastFrame = 0;
        still = running && ThemeEffect.isStill(getContext());
        if (running) invalidate();
        else removeCallbacks(frame);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        int lineColor = Colors.blend(accent, mixWith, 0.28f) | 0xFF000000;
        int clear = Colors.withAlpha(lineColor, 0);
        // Fades out at both edges: the colour of the line, opaque in the middle.
        line.setShader(new LinearGradient(0, 0, width, 0, new int[]{clear, lineColor, lineColor, clear},
                new float[]{0f, 0.16f, 0.84f, 1f}, Shader.TileMode.CLAMP));
        float dp = Dimens.dpExact(getContext(), 1);
        float radius = 42f * dp;
        cut.setShader(new RadialGradient(width / 2f, height / 2f, radius, new int[]{0xFF000000, 0xFF000000, 0x00000000},
                new float[]{0f, 30f / 42f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Only the button is touchable; the lines around it are not.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float reach = 36f * Dimens.dpExact(getContext(), 1);
            touching = Math.hypot(event.getX() - getWidth() / 2f, event.getY() - getHeight() / 2f) <= reach;
        }
        if (!touching) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                press = 1f;
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                press = 0f;
                animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                invalidate();
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
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float seconds = lastFrame == 0 ? 0f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (running && !still) {
            level = WaveLevel.follow(level, WaveLevel.target(playing, AudioLevel.current(), volume(), now / 1000.0), seconds);
            phase += seconds * WaveLevel.speed(level);
        } else {
            level = playing ? 0.5f : 0f;
        }
        float markTarget = playing ? 1f : 0f;
        markProgress += (markTarget - markProgress) * Math.min(1f, seconds * 14f);
        if (still || !running) markProgress = markTarget;

        float width = getWidth(), h = getHeight();
        float unit = width / DESIGN_WIDTH;
        float mid = h / 2f;
        float dp = Dimens.dpExact(getContext(), 1);
        float swing = WaveLevel.amplitude(level) * unit;
        float frequency = WaveLevel.frequency(level);
        float weight = WaveLevel.weight(level);

        int layer = canvas.saveLayer(0, 0, width, h, null);
        for (int i = 0; i < LINES; i++) {
            path.reset();
            for (int x = 0; x <= DESIGN_WIDTH; x += STEP) {
                float y = mid + swing * SWING[i]
                        * (float) Math.sin(K[i] * frequency * (x - DESIGN_WIDTH / 2f) + phase * SPEED[i] + SHIFT[i]);
                if (x == 0) path.moveTo(x * unit, y);
                else path.lineTo(x * unit, y);
            }
            line.setAlpha(Math.round(255 * OPACITY[i]));
            line.setStrokeWidth(WIDTH[i] * weight * dp * 1.2f);
            canvas.drawPath(path, line);
        }
        // The hole under the button: the lines do not run into the ring.
        canvas.drawCircle(width / 2f, mid, 42f * dp, cut);
        canvas.restoreToCount(layer);

        drawButton(canvas, width / 2f, mid, dp);
        scheduleNext();
    }

    private void drawButton(Canvas canvas, float cx, float cy, float dp) {
        float radius = 28f * dp;
        float stroke = 1.5f * dp * (1f + 0.5f * press);
        ring.setStrokeWidth(stroke);
        ring.setColor(Colors.withAlpha(ink, 0xE6));
        canvas.drawCircle(cx, cy, radius - stroke / 2f, ring);
        float size = 11f * dp;
        float t = markProgress;
        // The play triangle fades out narrowing, the pause bars grow in.
        if (t < 0.5f) {
            float scale = 1f - t * 2f;
            float shift = size * 0.18f;
            trianglePath.reset();
            trianglePath.moveTo(cx - size * 0.75f + shift, cy - size);
            trianglePath.lineTo(cx + size * Math.max(0.05f, scale) + shift, cy);
            trianglePath.lineTo(cx - size * 0.75f + shift, cy + size);
            trianglePath.close();
            mark.setAlpha(Math.round(255 * scale));
            canvas.drawPath(trianglePath, mark);
        } else {
            float grow = (t - 0.5f) * 2f;
            float bar = size * 0.42f, gap = size * 0.28f, round = bar / 2f;
            mark.setAlpha(Math.round(255 * grow));
            canvas.drawRoundRect(cx - gap - bar, cy - size * grow, cx - gap, cy + size * grow, round, round, mark);
            canvas.drawRoundRect(cx + gap, cy - size * grow, cx + gap + bar, cy + size * grow, round, round, mark);
        }
        mark.setAlpha(255);
    }

    /** The player's volume, 0 to 1, for when the loudness of the music is not known. */
    private float volume() {
        if (audio == null) return 0.6f;
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0.6f : audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    /** The next picture: at once while the music plays or the strip still settles, a few times a second at rest. */
    private void scheduleNext() {
        if (!running || still) return;
        removeCallbacks(frame);
        boolean moving = playing || level > 0.02f || Math.abs(markProgress - (playing ? 1f : 0f)) > 0.01f;
        if (moving) postInvalidateOnAnimation();
        else postDelayed(frame, RESTING_FRAME_MS);
    }
}
