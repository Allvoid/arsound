package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.TimeZone;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.player.ListenBuckets;
import app.revanced.extension.soundcloud.player.ListeningStats;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Colors;

/**
 * What was listened to and for how long, kept on this phone only. The results are three cards: the totals with a bar
 * chart of the time by day, month or year, the top tracks and the top artists, each top a ranked list whose thin bar
 * shows the plays against the most played.
 */
final class StatsScreen extends SectionScreen {
    static final String ID = "stats";

    StatsScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Статистика прослушиваний", "Listening statistics");
    }

    @Override
    String description() {
        return text("Сколько и что вы слушали — только на этом телефоне", "What and how much you listened to, on this phone only");
    }

    @Override
    boolean searched() {
        return false;
    }

    @Override
    String iconKey() {
        return "stats";
    }

    @Override
    String summary() {
        return ListeningStats.isEnabled() ? text("Сбор включён, данные только на телефоне", "Collecting, data stays on the phone")
                : text("Сбор выключен", "Collection is off");
    }

    @Override
    void fill(LinearLayout list) {
        // The cards of the numbers are filled in the background.
        LinearLayout content = ui.createColumn();
        list.addView(ui.createSubHeading(text("Сбор", "Collection")));
        list.addView(ui.createToggleRow(
                text("Собирать статистику", "Collect statistics"),
                text("Запоминает, какие треки и сколько времени играли. Хранится только на этом телефоне, никуда не отправляется. "
                                + "Прослушиванием считается от 30 секунд.",
                        "Remembers which tracks played and for how long. Kept only on this phone, never sent anywhere. "
                                + "A play counts from 30 seconds on."),
                ListeningStats.isEnabled(),
                (button, checked) -> Settings.putBoolean(ListeningStats.ENABLED, checked)
        ).icon("stats").brief(text("Какие треки и сколько играли — только на этом телефоне.",
                "Which tracks played and for how long, on this phone only.")));
        list.addView(ui.createChoiceRow(text("Период", "Period"), null, "stats_period", 30,
                new long[]{7, 30, 365, 0},
                new String[]{text("7 дней", "7 days"), text("30 дней", "30 days"), text("Год", "Year"), text("Всё время", "All time")},
                () -> showStats(content)).icon("clock"));
        list.addView(ui.createSubHeading(text("Результаты", "Results")));
        list.addView(content);
        showStats(content);
        list.addView(ui.createSubHeading(text("Удаление", "Removal")));
        list.addView(ui.createActionRow(text("Очистить статистику", "Clear statistics"),
                text("Удалить всю историю прослушиваний с телефона.", "Delete the whole listening history from the phone."),
                v -> new ArsoundDialog(activity)
                        .setTitle(text("Очистить статистику?", "Clear statistics?"))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(text("Очистить", "Clear"), (dialog, which) -> {
                            ListeningStats.clear();
                            showStats(content);
                        })
                        .show()).icon("trash").danger());
    }

    private void showStats(LinearLayout content) {
        // Counts run in parallel and can finish in any order: only the latest call may paint.
        Object token = new Object();
        content.setTag(token);
        content.removeAllViews();
        SettingsGroup waiting = card();
        TextView counting = ui.createText("Body.Secondary", text("Считаю…", "Counting…"));
        counting.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16));
        waiting.addView(counting);
        content.addView(waiting);
        long days = Settings.getLong("stats_period", 30);
        long since = days == 0 ? 0 : System.currentTimeMillis() - days * 24L * 60 * 60 * 1000;
        ListenBuckets buckets = ListenBuckets.forPeriod(days, System.currentTimeMillis(), TimeZone.getDefault());
        Utils.runOnBackgroundThread(() -> {
            ListeningStats.Summary summary = ListeningStats.summarize(since, 10, buckets);
            Utils.runOnMainThread(() -> {
                if (content.getTag() != token || activity.isFinishing() || activity.isDestroyed()) return;
                content.removeAllViews();
                content.addView(createTotals(summary));
                addTop(content, text("Треки", "Tracks"), summary.topTracks, true);
                addTop(content, text("Исполнители", "Artists"), summary.topArtists, false);
            });
        });
    }

    /** A card of the results: no lines inside, the rows keep apart by their spacing. */
    private SettingsGroup card() {
        SettingsGroup card = new SettingsGroup(ui).withoutDividers();
        card.setLayoutParams(SettingsGroup.pageParams(ui));
        return card;
    }

    /** The time listened in large type, the number of plays and of different tracks side by side under it. */
    private View createTotals(ListeningStats.Summary summary) {
        SettingsGroup card = card();
        LinearLayout box = ui.createColumn();
        box.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(18));
        box.addView(ui.createText("Body.Secondary", text("Прослушано", "Listened")));
        box.addView(ui.createText("H1.Primary", duration(summary.playedMs)));
        LinearLayout figures = new LinearLayout(activity);
        figures.setOrientation(LinearLayout.HORIZONTAL);
        figures.setPadding(0, ui.dp(12), 0, 0);
        figures.addView(createFigure(summary.plays, text(Numerals.russianForm(summary.plays, "прослушивание",
                "прослушивания", "прослушиваний"), summary.plays == 1 ? "play" : "plays")), figureParams());
        figures.addView(createFigure(summary.tracks, text(Numerals.russianForm(summary.tracks, "разный трек",
                "разных трека", "разных треков"), summary.tracks == 1 ? "different track" : "different tracks")), figureParams());
        box.addView(figures);
        if (summary.buckets != null && summary.playedMs > 0) {
            // The time by day, month or year, in the same card: no new row on the screen.
            TextView sample = ui.createText("Body.Primary", "");
            StatsChart chart = new StatsChart(activity, ui.accent(), sample.getCurrentTextColor(), ui.secondary(),
                    sample.getTypeface(), StatsScreen::duration);
            LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chartParams.topMargin = ui.dp(16);
            box.addView(chart, chartParams);
            chart.setData(summary.buckets);
        }
        card.addView(box);
        return card;
    }

    private LinearLayout.LayoutParams figureParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private View createFigure(int value, String label) {
        LinearLayout figure = ui.createColumn();
        TextView number = ui.createText("H3.Primary", String.valueOf(value));
        number.setFontFeatureSettings("tnum");
        figure.addView(number);
        figure.addView(ui.createText("Body.Secondary", label));
        return figure;
    }

    /** A heading and a card of ranked rows; nothing when the list is empty. */
    private void addTop(LinearLayout content, String title, List<ListeningStats.Entry> entries, boolean tracks) {
        if (entries.isEmpty()) return;
        content.addView(ui.createSubHeading(title));
        SettingsGroup card = card();
        card.setPadding(0, ui.dp(6), 0, ui.dp(6));
        // The places go by plays, and so do the bars.
        int most = 1;
        for (ListeningStats.Entry entry : entries) most = Math.max(most, entry.plays);
        for (int i = 0; i < entries.size(); i++) {
            ListeningStats.Entry entry = entries.get(i);
            String below = tracks ? (entry.artist == null ? "" : entry.artist) : null;
            card.addView(createRankRow(i + 1, entry, below, entry.plays / (float) most));
        }
        content.addView(card);
    }

    /**
     * One place of a top: the place in the accent colour, the title and (for a track) the artist, the time and the
     * number of plays at the end, and a thin bar of the plays against the most played. The end column has one width
     * in every row, so the bars end on one line.
     */
    private View createRankRow(int place, ListeningStats.Entry entry, String below, float share) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(14), ui.dp(9), ui.dp(16), ui.dp(9));

        TextView rank = ui.createText("H4.Primary", String.valueOf(place));
        rank.setTextColor(ui.accent());
        rank.setFontFeatureSettings("tnum");
        rank.setGravity(Gravity.CENTER);
        row.addView(rank, new LinearLayout.LayoutParams(ui.dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout middle = ui.createColumn();
        middle.setPadding(ui.dp(8), 0, ui.dp(12), 0);
        middle.addView(oneLine(ui.createText("Body.Primary", entry.title)));
        if (below != null && !below.isEmpty()) middle.addView(oneLine(ui.createText("Body.Secondary", below)));
        middle.addView(createShareBar(share));
        row.addView(middle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout end = ui.createColumn();
        end.setGravity(Gravity.END);
        end.setMinimumWidth(ui.dp(72));
        TextView time = ui.createText("Body.Primary", duration(entry.playedMs));
        time.setFontFeatureSettings("tnum");
        end.addView(time);
        TextView plays = ui.createText("Body.Secondary", playsText(entry.plays));
        plays.setFontFeatureSettings("tnum");
        end.addView(plays);
        row.addView(end);
        return row;
    }

    private static TextView oneLine(TextView view) {
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    /** A rounded bar as long as the share of the row's time, on a faint track of the full width. */
    private View createShareBar(float share) {
        LinearLayout track = new LinearLayout(activity);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable trackShape = new GradientDrawable();
        trackShape.setCornerRadius(ui.dp(2));
        trackShape.setColor(Colors.withAlpha(ui.accent(), 0x1A));
        track.setBackground(trackShape);
        View fill = new View(activity);
        GradientDrawable fillShape = new GradientDrawable();
        fillShape.setCornerRadius(ui.dp(2));
        fillShape.setColor(Colors.withAlpha(ui.accent(), 0x99));
        fill.setBackground(fillShape);
        float filled = Math.max(0.03f, Math.min(1f, share));
        track.addView(fill, new LinearLayout.LayoutParams(0, ui.dp(3), filled));
        track.addView(new View(activity), new LinearLayout.LayoutParams(0, ui.dp(3), 1f - filled));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(3));
        params.topMargin = ui.dp(6);
        track.setLayoutParams(params);
        return track;
    }

    /** "1 раз", "3 раза", "5 раз"; in English "1 play", "3 plays". */
    private static String playsText(int plays) {
        return text(plays + " " + Numerals.russianForm(plays, "раз", "раза", "раз"), plays + (plays == 1 ? " play" : " plays"));
    }

    static String duration(long ms) {
        long minutes = ms / 60_000;
        if (minutes == 0) return text(ms / 1000 + " с", ms / 1000 + " s");
        if (minutes < 60) return text(minutes + " мин", minutes + " min");
        return text(minutes / 60 + " ч " + minutes % 60 + " мин", minutes / 60 + " h " + minutes % 60 + " min");
    }
}
