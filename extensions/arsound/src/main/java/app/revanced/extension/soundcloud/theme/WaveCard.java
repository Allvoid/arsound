package app.revanced.extension.soundcloud.theme;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.soundcloud.recommendations.MyWave;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;

/**
 * The card "My Wave" under the greeting of the home screen: a tap starts music that follows the taste and does not end,
 * a tap while it plays stops it. The card is in the accent of the theme; the bars on it move while the wave plays.
 */
public final class WaveCard extends LinearLayout {
    private final Bars bars;
    private final TextView subtitle;

    public WaveCard(Context context) {
        this(context, false);
    }

    /** @param big The card of the wave screen: taller, with a bigger title. */
    public WaveCard(Context context, boolean big) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int accent = ArsoundTheme.palette(context, "special");
        int onAccent = Colors.contrast(accent);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{accent, Colors.blend(accent, Color.BLACK, 0.35f)});
        shape.setCornerRadius(Dimens.dp(context, 18));
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null));
        int pad = Dimens.dp(context, big ? 22 : 14);
        setPadding(Dimens.dp(context, 16), pad, Dimens.dp(context, 16), pad);

        bars = new Bars(context, onAccent);
        addView(bars, new LayoutParams(Dimens.dp(context, big ? 56 : 36), Dimens.dp(context, big ? 56 : 36)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(VERTICAL);
        TextView title = new TextView(context);
        title.setText(text("Моя волна", "My Wave"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, big ? 24 : 19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(onAccent);
        texts.addView(title);
        subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setTextColor(Colors.withAlpha(onAccent, 0xCC));
        texts.addView(subtitle);
        LayoutParams textParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = Dimens.dp(context, 14);
        addView(texts, textParams);

        TextView action = new TextView(context);
        action.setTag("action");
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setTextColor(accent);
        action.setGravity(Gravity.CENTER);
        action.setPadding(Dimens.dp(context, 16), Dimens.dp(context, 8), Dimens.dp(context, 16), Dimens.dp(context, 8));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(Dimens.dp(context, 100));
        pill.setColor(onAccent);
        action.setBackground(pill);
        addView(action, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        setOnClickListener(v -> {
            if (MyWave.isActive()) MyWave.stop(context);
            else MyWave.start(context);
            refresh();
            // The first batch takes a moment to come: the card looks again a little later.
            postDelayed(this::refresh, 1500);
            postDelayed(this::refresh, 5000);
        });
        refresh();
    }

    private void refresh() {
        boolean on = MyWave.isActive();
        subtitle.setText(on ? text("Играет по вашему вкусу, без конца", "Playing to your taste, without end")
                : text("Музыка по вашему вкусу, без конца", "Music to your taste, without end"));
        ((TextView) findViewWithTag("action")).setText(on ? text("Стоп", "Stop") : text("Включить", "Play"));
        bars.setMoving(on && isAttachedToWindow());
        setContentDescription(on ? text("Моя волна играет. Остановить", "My Wave is playing. Stop")
                : text("Включить Мою волну", "Play My Wave"));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        bars.setMoving(false);
        super.onDetachedFromWindow();
    }

    /** Five rounded bars that rise and fall while the wave plays. */
    private static final class Bars extends View {
        private static final float[] REST = {0.35f, 0.6f, 0.9f, 0.6f, 0.35f};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private ValueAnimator animator;
        private float phase;

        Bars(Context context, int color) {
            super(context);
            paint.setColor(color);
        }

        void setMoving(boolean moving) {
            if (moving == (animator != null)) return;
            if (!moving) {
                animator.cancel();
                animator = null;
                phase = 0;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
            animator.setDuration(1200);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(null);
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth(), height = getHeight();
            float bar = width / 9f;
            for (int i = 0; i < REST.length; i++) {
                float level = animator == null ? REST[i]
                        : 0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(phase + i * 1.1f));
                float barHeight = height * level;
                float left = bar * (1 + i * 2f);
                rect.set(left, (height - barHeight) / 2f, left + bar, (height + barHeight) / 2f);
                canvas.drawRoundRect(rect, bar / 2f, bar / 2f, paint);
            }
        }
    }
}
