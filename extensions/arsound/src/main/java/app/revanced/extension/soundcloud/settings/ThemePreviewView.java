package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * A small drawing of the app in a theme: the title, a card with two tracks, a play button, the mini player and the tab
 * bar. It is drawn from the theme's colours on every change, so it can follow a finger on a colour picker; the real
 * screens read their colours once, so they cannot show a change live.
 */
public final class ThemePreviewView extends View {
    /** The drawing is made on a canvas of this size and scaled to the view. */
    private static final float W = 320f, H = 292f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final Path glyph = new Path();
    private ArsoundTheme.Theme theme;
    private boolean night = true;

    public ThemePreviewView(Context context) {
        super(context);
    }

    /** @param night Which look of the theme to draw. */
    public void setTheme(ArsoundTheme.Theme theme, boolean night) {
        this.theme = theme;
        this.night = night;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? Dimens.dp(getContext(), 280) : MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.round(width * H / W));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (theme == null) return;
        float scale = getWidth() / W;
        canvas.save();
        canvas.scale(scale, scale);

        int bg = theme.token(night, "bg"), card = theme.token(night, "surface"), card2 = theme.token(night, "surface2");
        int accent = theme.token(night, "accent"), onAccent = theme.token(night, "onAccent");
        int muted = theme.token(night, "muted"), tabbar = theme.token(night, "tabbar");
        int text = theme.color(night, "primary");
        // The corners follow the slider over its whole range; the shapes are small, so the renderer limits a radius to half a side.
        float radius = theme.cardRadius();

        // The screen, rounded like a phone's.
        rect.set(0, 0, W, H);
        clip.reset();
        clip.addRoundRect(rect, 20, 20, Path.Direction.CW);
        canvas.clipPath(clip);
        fill(canvas, 0, 0, W, H, 0, bg);

        // The title row.
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(19);
        paint.setColor(text);
        canvas.drawText("Arsound", 16, 38, paint);
        circle(canvas, 288, 31, 13, card2);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(muted);
        canvas.drawCircle(286, 29.5f, 4.5f, paint);
        canvas.drawLine(289.3f, 32.8f, 293f, 36.5f, paint);
        paint.setStyle(Paint.Style.FILL);

        // The card with two tracks: a cover in the accent, a cover in the card colour.
        fill(canvas, 12, 54, W - 12, 158, Math.max(radius, 4) + 4, card);
        track(canvas, 64, accent, radius, text, muted, text("Ночная дорога", "Midnight drive"), text("Ночные сеты", "Night Sessions"), true);
        track(canvas, 108, card2, radius, text, muted, text("Тихий свет", "Slow light"), text("Радио Arsound", "Arsound Radio"), false);

        // The play button and a quiet one beside it.
        fill(canvas, 16, 168, 136, 202, 17, accent);
        glyph(canvas, 36, 185, 7, onAccent);
        paint.setTextSize(13.5f);
        paint.setColor(onAccent);
        canvas.drawText(text("Играть", "Play"), 52, 189.5f, paint);
        rect.set(146, 168, 266, 202);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(theme.token(night, "border"));
        canvas.drawRoundRect(rect, 17, 17, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(theme.token(night, "text2"));
        canvas.drawText(text("Вперемешку", "Shuffle"), 170, 189.5f, paint);

        // The mini player.
        float miniRadius = theme.cardRadius() + 6;
        fill(canvas, 10, 212, W - 10, 252, miniRadius, theme.token(night, "mini"));
        rect.set(10, 212, W - 10, 252);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(theme.token(night, "miniBorder"));
        canvas.drawRoundRect(rect, miniRadius, miniRadius, paint);
        paint.setStyle(Paint.Style.FILL);
        fill(canvas, 18, 218, 46, 246, radius, accent);
        paint.setTextSize(12.5f);
        paint.setColor(text);
        canvas.drawText(text("Ночная дорога", "Midnight drive"), 56, 229, paint);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(10.5f);
        paint.setColor(muted);
        canvas.drawText(text("Ночные сеты", "Night Sessions"), 56, 243, paint);
        circle(canvas, 280, 232, 14, accent);
        glyph(canvas, 280, 232, 6, onAccent);

        // The tab bar: the active tab in the accent.
        fill(canvas, 0, 258, W, H, 0, tabbar);
        for (int i = 0; i < 4; i++) {
            float x = 40 + i * 80;
            int color = i == 0 ? accent : muted;
            paint.setColor(color);
            circle(canvas, x, 272, 6.5f, color);
            rect.set(x - 14, 283.5f, x + 14, 286.5f);
            canvas.drawRoundRect(rect, 1.5f, 1.5f, paint);
        }
        canvas.restore();
    }

    /** One track of the card: the cover, the title and the artist. */
    private void track(Canvas canvas, float top, int cover, float radius, int text, int muted, String title, String artist,
                       boolean gradient) {
        float r = radius;
        if (gradient) {
            rect.set(24, top, 64, top + 40);
            paint.setShader(new LinearGradient(24, top, 64, top + 40, cover, theme.token(night, "pink"), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setShader(null);
        } else {
            fill(canvas, 24, top, 64, top + 40, r, cover);
        }
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(14);
        paint.setColor(text);
        canvas.drawText(title, 76, top + 17, paint);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(11.5f);
        paint.setColor(muted);
        canvas.drawText(artist, 76, top + 33, paint);
    }

    private void fill(Canvas canvas, float left, float top, float right, float bottom, float radius, int color) {
        paint.setColor(color);
        rect.set(left, top, right, bottom);
        if (radius > 0) canvas.drawRoundRect(rect, radius, radius, paint);
        else canvas.drawRect(rect, paint);
    }

    private void circle(Canvas canvas, float x, float y, float radius, int color) {
        paint.setColor(color);
        canvas.drawCircle(x, y, radius, paint);
    }

    /** A play triangle centred at x, y. */
    private void glyph(Canvas canvas, float x, float y, float size, int color) {
        glyph.reset();
        glyph.moveTo(x - size * .55f, y - size);
        glyph.lineTo(x + size * .95f, y);
        glyph.lineTo(x - size * .55f, y + size);
        glyph.close();
        paint.setColor(color);
        canvas.drawPath(glyph, paint);
    }
}
