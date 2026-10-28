package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.player.ListeningStats;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/** What was listened to and for how long, kept on this phone only. */
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
        // The numbers sit in a card of their own; it is filled in the background.
        LinearLayout content = new SettingsGroup(ui);
        content.setLayoutParams(SettingsGroup.pageParams(ui));
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
        TextView counting = ui.createText("Body.Secondary", text("Считаю…", "Counting…"));
        counting.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        content.addView(counting);
        long days = Settings.getLong("stats_period", 30);
        long since = days == 0 ? 0 : System.currentTimeMillis() - days * 24L * 60 * 60 * 1000;
        Utils.runOnBackgroundThread(() -> {
            ListeningStats.Summary summary = ListeningStats.summarize(since, 10);
            Utils.runOnMainThread(() -> {
                if (content.getTag() != token || activity.isFinishing() || activity.isDestroyed()) return;
                content.removeAllViews();
                TextView total = ui.createText("H4.Primary", text("Прослушано: ", "Listened: ") + duration(summary.playedMs));
                total.setPadding(ui.dimen("spacing_m"), ui.dimen("spacing_s"), ui.dimen("spacing_m"), 0);
                content.addView(total);
                TextView counts = ui.createText("Body.Secondary", text("Прослушиваний: " + summary.plays + " · разных треков: " + summary.tracks,
                        "Plays: " + summary.plays + " · different tracks: " + summary.tracks));
                counts.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), ui.dimen("spacing_s"));
                content.addView(counts);
                if (summary.topTracks.isEmpty()) return;
                content.addView(createStatsHeading(text("Треки", "Tracks")));
                for (ListeningStats.Entry entry : summary.topTracks) {
                    content.addView(createStatRow(entry.title, (entry.artist == null || entry.artist.isEmpty() ? "" : entry.artist + " · ")
                            + playsText(entry.plays) + " · " + duration(entry.playedMs)));
                }
                if (summary.topArtists.isEmpty()) return;
                content.addView(createStatsHeading(text("Исполнители", "Artists")));
                for (ListeningStats.Entry entry : summary.topArtists) {
                    content.addView(createStatRow(entry.title, playsText(entry.plays) + " · " + duration(entry.playedMs)));
                }
            });
        });
    }

    /** A small accent title inside the card of the numbers. */
    private View createStatsHeading(String label) {
        View heading = ui.createSubHeading(label);
        heading.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(4));
        return heading;
    }

    private View createStatRow(String title, String details) {
        LinearLayout row = ui.createColumn();
        row.setPadding(ui.dimen("spacing_m"), ui.dimen("spacing_xs"), ui.dimen("spacing_m"), ui.dimen("spacing_xs"));
        row.addView(ui.createText("Body.Primary", title));
        row.addView(ui.createText("Body.Secondary", details));
        return row;
    }

    private static String playsText(int plays) {
        return text(plays + " прослуш.", plays + (plays == 1 ? " play" : " plays"));
    }

    private static String duration(long ms) {
        long minutes = ms / 60_000;
        if (minutes == 0) return text(ms / 1000 + " с", ms / 1000 + " s");
        if (minutes < 60) return text(minutes + " мин", minutes + " min");
        return text(minutes / 60 + " ч " + minutes % 60 + " мин", minutes / 60 + " h " + minutes % 60 + " min");
    }
}
