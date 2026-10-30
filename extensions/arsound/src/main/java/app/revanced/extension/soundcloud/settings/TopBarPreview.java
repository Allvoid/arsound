package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.wallpaper.TopBarLook;
import app.revanced.extension.soundcloud.wallpaper.TopBarStyle;
import app.revanced.extension.soundcloud.wallpaper.WallpaperStore;

/**
 * The top of a phone's screen in one {@link TopBarStyle}: the user's wallpaper as the screens draw it, a playlist
 * scrolled so that a track is under the top bar, and the bar with its back button, "Search in playlist" and the
 * filter, in the proportions of the real one. The bar is drawn as the app draws it, the glass from the same blurred
 * strip of the wallpaper.
 */
final class TopBarPreview extends View {
    /** The phone's height for its width; the wallpaper and the glass are laid out for the whole screen. */
    private static final float SHAPE = 2.05f;
    /** The height of the part of the screen shown, for its width. */
    static final float TILE = 1.3f;
    /** The bar's height for the screen's width: the top bar with the status bar above it. */
    private static final float BAR = 0.335f;

    private final TopBarStyle style;
    private final Drawable wallpaper;
    private final int surface, accent;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private boolean chosen;

    TopBarPreview(Context context, TopBarStyle style, int surface, int accent) {
        super(context);
        this.style = style;
        this.surface = surface | 0xFF000000;
        this.accent = accent;
        Drawable picture = WallpaperStore.background(context);
        this.wallpaper = picture;
        label.setColor(0xD9FFFFFF);
        setContentDescription(style.label());
    }

    void setChosen(boolean chosen) {
        this.chosen = chosen;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth(), height = getHeight();
        float density = getResources().getDisplayMetrics().density;
        float radius = 14 * density;
        clip.reset();
        rect.set(0, 0, width, height);
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);

        // The wallpaper, as the screens draw it: laid out for the whole screen, its top shown.
        if (wallpaper != null) {
            wallpaper.setBounds(0, 0, Math.round(width), Math.round(width * SHAPE));
            wallpaper.draw(canvas);
        } else {
            canvas.drawColor(surface);
        }
        float bar = width * BAR;
        drawRows(canvas, width, height, bar, density);
        drawBar(canvas, width, bar, density);
        canvas.restore();

        // The frame, in the accent when this style is chosen.
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((chosen ? 2.5f : 1f) * density);
        paint.setColor(chosen ? accent : 0x40FFFFFF);
        float inset = paint.getStrokeWidth() / 2;
        rect.set(inset, inset, width - inset, height - inset);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** A few tracks of a list; the first of them half under the bar. */
    private void drawRows(Canvas canvas, float width, float height, float bar, float density) {
        float rowHeight = width * 0.21f;
        float cover = rowHeight * 0.8f;
        float side = width * 0.08f;
        int[] covers = {0xFFD8D2BA, 0xFF3A3F47, 0xFF9AA6B2, 0xFFC94B3C, 0xFF4B3A2F, 0xFF8A96A8, 0xFFE2DCC4, 0xFF2C3340};
        for (int i = 0; i < 8; i++) {
            float top = bar - rowHeight * 0.6f + i * rowHeight;
            if (top > height) break;
            paint.setShader(null);
            paint.setColor(covers[i]);
            rect.set(side, top + (rowHeight - cover) / 2, side + cover, top + (rowHeight + cover) / 2);
            canvas.drawRoundRect(rect, 2 * density, 2 * density, paint);
            float text = side + cover + width * 0.06f;
            paint.setColor(0xE6FFFFFF);
            rect.set(text, top + rowHeight * 0.3f, text + width * (0.42f + (i % 3) * 0.06f), top + rowHeight * 0.42f);
            canvas.drawRoundRect(rect, 2 * density, 2 * density, paint);
            paint.setColor(0x80FFFFFF);
            rect.set(text, top + rowHeight * 0.55f, text + width * 0.28f, top + rowHeight * 0.64f);
            canvas.drawRoundRect(rect, 2 * density, 2 * density, paint);
        }
    }

    /** The band in this style, then the back button, the field and the filter on it. */
    private void drawBar(Canvas canvas, float width, float bar, float density) {
        paint.setShader(null);
        switch (style) {
            case SOUNDCLOUD:
                paint.setColor(surface);
                canvas.drawRect(0, 0, width, bar, paint);
                break;
            case TINT:
                paint.setColor(Colors.withAlpha(surface, 0x8C));
                canvas.drawRect(0, 0, width, bar, paint);
                break;
            case FADE:
                paint.setShader(new LinearGradient(0, 0, 0, bar,
                        new int[]{Colors.withAlpha(surface, 0xE6), Colors.withAlpha(surface, 0x8C), Colors.withAlpha(surface, 0)},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, width, bar, paint);
                break;
            case GLASS:
                drawGlass(canvas, width, bar, density);
                break;
            default:
                break;
        }
        paint.setShader(null);

        // The controls, with their own backgrounds as SoundCloud draws them, where the real ones are.
        float centre = width * 0.26f;
        float button = width * 0.09f;
        float side = width * 0.04f;
        int pill = Colors.withAlpha(0xFF283450, 0xD9);
        paint.setColor(pill);
        canvas.drawCircle(side + button / 2, centre, button / 2, paint);
        float fieldLeft = side + button + width * 0.04f, fieldRight = width - side - button * 0.9f;
        rect.set(fieldLeft, centre - button / 2, fieldRight, centre + button / 2);
        canvas.drawRoundRect(rect, button / 2, button / 2, paint);

        // The arrow of the back button.
        paint.setColor(0xFFFFFFFF);
        paint.setStrokeWidth(1.4f * density);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float arrow = button * 0.22f, x = side + button / 2;
        canvas.drawLine(x - arrow, centre, x + arrow, centre, paint);
        canvas.drawLine(x - arrow, centre, x - arrow * 0.2f, centre - arrow * 0.8f, paint);
        canvas.drawLine(x - arrow, centre, x - arrow * 0.2f, centre + arrow * 0.8f, paint);
        // The filter: three sliders.
        float filter = width - side - button * 0.45f;
        for (int i = -1; i <= 1; i++) {
            float fx = filter + i * button * 0.18f;
            canvas.drawLine(fx, centre - button * 0.25f, fx, centre + button * 0.25f, paint);
        }
        paint.setStyle(Paint.Style.FILL);

        // "Search in playlist", cut to the field.
        label.setTextSize(button * 0.42f);
        float textLeft = fieldLeft + button * 0.45f;
        CharSequence hint = TextUtils.ellipsize(text("Поиск в плейлисте", "Search in playlist"), label,
                fieldRight - textLeft - button * 0.3f, TextUtils.TruncateAt.END);
        canvas.drawText(hint, 0, hint.length(), textLeft, centre + label.getTextSize() * 0.35f, label);
    }

    /** The glass as the app lays it: the blurred strip, the tint, the sheen and the light edge. */
    private void drawGlass(Canvas canvas, float width, float bar, float density) {
        Bitmap strip = TopBarLook.glassStrip();
        if (strip != null) {
            BitmapShader shader = new BitmapShader(strip, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix matrix = TopBarLook.stripMatrix(strip, Math.round(width), Math.round(width * SHAPE));
            shader.setLocalMatrix(matrix);
            paint.setShader(shader);
            canvas.drawRect(0, 0, width, bar, paint);
            paint.setShader(null);
        }
        paint.setColor(TopBarLook.glassTint(surface));
        canvas.drawRect(0, 0, width, bar, paint);
        paint.setShader(new LinearGradient(0, 0, 0, bar, new int[]{0x1AFFFFFF, 0x08FFFFFF, 0x03FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, bar, paint);
        paint.setShader(null);
        paint.setColor(0x3DFFFFFF);
        canvas.drawRect(0, bar - Math.max(1f, density), width, bar, paint);
    }
}
