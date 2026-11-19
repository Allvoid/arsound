package app.revanced.extension.soundcloud.stats;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.List;

import app.revanced.extension.soundcloud.player.YearReview;
import app.revanced.extension.soundcloud.settings.Numerals;

/**
 * The card of the year in review drawn into a picture: the same tiles and lists as the desktop card (time listened,
 * the artist of the period, the busiest day, days in a row, new artists, the two tops). The picture is shown on the
 * screen and shared as it is. Drawn in two passes with one code: the first only measures the height.
 */
public final class ReviewCard {
    static final int WIDTH = 1080;
    private static final int PAD = 56;
    private static final int GAP = 24;
    private static final int INNER = 36;

    private static final int BACKGROUND = 0xFF111118;
    private static final int TILE = 0xFF1D1D27;
    private static final int PRIMARY = 0xFFF2F2F7;
    private static final int SECONDARY = 0xFF9A9AAA;

    private final YearReview.Result review;
    private final String title;
    private final int accent;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ReviewCard(YearReview.Result review, String title, int accent) {
        this.review = review;
        this.title = title;
        this.accent = accent;
    }

    /**
     * Draws the card. Call it on a background thread: the picture is large.
     *
     * @param title  "2026", or the name of the period.
     * @param accent The accent colour of the app.
     */
    public static Bitmap render(YearReview.Result review, String title, int accent) {
        ReviewCard card = new ReviewCard(review, title, accent);
        int height = card.draw(null);
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888);
        card.draw(new Canvas(bitmap));
        return bitmap;
    }

    /** Draws onto the canvas, or only measures when it is null. @return The height of the card. */
    private int draw(Canvas canvas) {
        if (canvas != null) canvas.drawColor(BACKGROUND);
        int y = PAD;
        y = line(canvas, text("ВАШЕ ПРОСЛУШИВАНИЕ", "YOUR LISTENING"), PAD, y, 30, accent, true, WIDTH - 2 * PAD);
        y = line(canvas, title, PAD, y + 8, 96, PRIMARY, true, WIDTH - 2 * PAD);
        y += GAP + 8;
        if (review.isEmpty()) {
            y = line(canvas, text("За это время ничего не слушали.", "Nothing was listened to in this time."),
                    PAD, y, 38, SECONDARY, false, WIDTH - 2 * PAD);
            y += GAP;
            return footer(canvas, y);
        }
        y = tile(canvas, y, text("Время прослушивания", "Time listened"), duration(review.totalMs), 132,
                text("Прослушиваний: " + review.plays + " · разных треков: " + review.tracks,
                        review.plays + " plays · " + review.tracks + " different tracks"), true);
        if (!review.topArtists.isEmpty()) {
            YearReview.ArtistTime top = review.topArtists.get(0);
            y = tile(canvas, y, text("Исполнитель периода", "Artist of the period"), top.name, 64,
                    duration(top.playedMs), false);
        }
        if (review.busiestMs > 0) {
            y = tile(canvas, y, text("Самый музыкальный день", "The busiest day"), date(), 56,
                    duration(review.busiestMs), false);
        }
        y = tile(canvas, y, text("Дней подряд", "Days in a row"), String.valueOf(review.longestStreak), 56,
                text(Numerals.russianForm(review.longestStreak, "день", "дня", "дней"),
                        review.longestStreak == 1 ? "day" : "days"), false);
        y = tile(canvas, y, text("Новые исполнители", "Artists heard first"), String.valueOf(review.newArtists), 56,
                TextUtils.join(", ", review.newArtistNames), false);
        if (review.unknownArtistPlays > 0) {
            // The artist lists leave these listens out: the card says so instead of showing a partial count as whole.
            int unknown = review.unknownArtistPlays;
            y = line(canvas, text("Без исполнителя: " + unknown + " " + Numerals.russianForm(unknown, "прослушивание",
                            "прослушивания", "прослушиваний") + ", в списках их нет",
                    "No artist for " + unknown + (unknown == 1 ? " listen" : " listens") + ": not in the artist lists"),
                    PAD, y, 30, SECONDARY, false, WIDTH - 2 * PAD) + GAP;
        }
        y = tracksList(canvas, y);
        y = artistsList(canvas, y);
        return footer(canvas, y);
    }

    private int footer(Canvas canvas, int y) {
        y = line(canvas, "Arsound", PAD, y + 8, 30, SECONDARY, true, WIDTH - 2 * PAD);
        return y + PAD;
    }

    /** One tile: the label, the value in large type and a note under it; the tile is drawn behind them. */
    private int tile(Canvas canvas, int top, String label, String value, int valueSize, String note, boolean big) {
        int inner = WIDTH - 2 * (PAD + INNER);
        int height = INNER + lineHeight(30) + 8 + lineHeight(valueSize) + (note.isEmpty() ? 0 : 8 + lineHeight(34)) + INNER;
        if (canvas != null) {
            fill.setColor(big ? mix(TILE, accent, 0.22f) : TILE);
            canvas.drawRoundRect(new RectF(PAD, top, WIDTH - PAD, top + height), 36, 36, fill);
        }
        int y = top + INNER;
        y = line(canvas, label, PAD + INNER, y, 30, SECONDARY, false, inner);
        y = line(canvas, value, PAD + INNER, y + 8, valueSize, big ? PRIMARY : accent, true, inner);
        if (!note.isEmpty()) line(canvas, note, PAD + INNER, y + 8, 34, SECONDARY, false, inner);
        return top + height + GAP;
    }

    private int tracksList(Canvas canvas, int top) {
        List<YearReview.TrackPlays> tracks = review.topTracks;
        if (tracks.isEmpty()) return top;
        int height = INNER + lineHeight(30) + 8 + tracks.size() * (lineHeight(38) + lineHeight(30) + 18) + INNER - 18;
        box(canvas, top, height);
        int inner = WIDTH - 2 * (PAD + INNER);
        int y = line(canvas, text("Чаще всего", "Played most"), PAD + INNER, top + INNER, 30, SECONDARY, false, inner);
        y += 8;
        int place = 1;
        for (YearReview.TrackPlays track : tracks) {
            line(canvas, String.valueOf(place++), PAD + INNER, y, 38, accent, true, 60);
            int next = line(canvas, track.title, PAD + INNER + 60, y, 38, PRIMARY, false, inner - 60);
            next = line(canvas, track.artist, PAD + INNER + 60, next, 30, SECONDARY, false, inner - 60);
            y = next + 18;
        }
        return top + height + GAP;
    }

    private int artistsList(Canvas canvas, int top) {
        List<YearReview.ArtistTime> artists = review.topArtists;
        if (artists.isEmpty()) return top;
        int height = INNER + lineHeight(30) + 8 + artists.size() * (lineHeight(38) + 18) + INNER - 18;
        box(canvas, top, height);
        int inner = WIDTH - 2 * (PAD + INNER);
        int y = line(canvas, text("Дольше всего", "Listened most"), PAD + INNER, top + INNER, 30, SECONDARY, false, inner);
        y += 8;
        int place = 1;
        for (YearReview.ArtistTime artist : artists) {
            line(canvas, String.valueOf(place++), PAD + INNER, y, 38, accent, true, 60);
            String time = duration(artist.playedMs);
            int timeWidth = (int) measure(time, 34, false) + 16;
            line(canvas, time, WIDTH - PAD - INNER - timeWidth + 16, y + 3, 34, SECONDARY, false, timeWidth);
            y = line(canvas, artist.name, PAD + INNER + 60, y, 38, PRIMARY, false, inner - 60 - timeWidth) + 18;
        }
        return top + height + GAP;
    }

    private void box(Canvas canvas, int top, int height) {
        if (canvas == null) return;
        fill.setColor(TILE);
        canvas.drawRoundRect(new RectF(PAD, top, WIDTH - PAD, top + height), 36, 36, fill);
    }

    /** One line of text cut to the width with "…". @return The y below the line. */
    private int line(Canvas canvas, String value, int x, int top, int size, int color, boolean bold, int width) {
        int height = lineHeight(size);
        if (canvas == null || value == null || value.isEmpty()) return top + height;
        style(size, color, bold);
        CharSequence cut = TextUtils.ellipsize(value, paint, width, TextUtils.TruncateAt.END);
        canvas.drawText(cut, 0, cut.length(), x, top - paint.ascent(), paint);
        return top + height;
    }

    private float measure(String value, int size, boolean bold) {
        style(size, PRIMARY, bold);
        return paint.measureText(value);
    }

    private int lineHeight(int size) {
        style(size, PRIMARY, false);
        return Math.round(paint.descent() - paint.ascent());
    }

    private void style(int size, int color, boolean bold) {
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private String date() {
        String[] russian = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября",
                "октября", "ноября", "декабря"};
        String[] english = {"January", "February", "March", "April", "May", "June", "July", "August", "September",
                "October", "November", "December"};
        int month = Math.max(1, Math.min(12, review.busiestMonth)) - 1;
        return text(review.busiestDay + " " + russian[month] + " " + review.busiestYear,
                english[month] + " " + review.busiestDay + ", " + review.busiestYear);
    }

    private static String duration(long ms) {
        long minutes = ms / 60_000;
        if (minutes == 0) return text(ms / 1000 + " с", ms / 1000 + " s");
        if (minutes < 60) return text(minutes + " мин", minutes + " min");
        return text(minutes / 60 + " ч " + minutes % 60 + " мин", minutes / 60 + " h " + minutes % 60 + " min");
    }

    private static int mix(int from, int to, float amount) {
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }
}
