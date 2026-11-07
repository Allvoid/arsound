package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.widget.LinearLayout;

import java.util.function.Consumer;
import java.util.function.Supplier;

import app.revanced.extension.soundcloud.player.AudioEqualizer;
import app.revanced.extension.soundcloud.player.SlowedPlayback;
import app.revanced.extension.soundcloud.player.TrackVersions;
import app.revanced.extension.soundcloud.player.VersionSpeed;
import app.revanced.extension.soundcloud.player.VersionStrength;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/**
 * The rows of the versions of a track in the playback settings: the speed, how slow and how fast, the reverb and the
 * bass boost. The same choices are in the small window of the button in the full-screen player.
 */
final class VersionRows {
    private final SettingsUi ui;

    VersionRows(SettingsUi ui) {
        this.ui = ui;
    }

    void addTo(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Версии трека", "Versions of the track")));
        speedRow = ui.createActionRow(
                text("Скорость и тон", "Speed and pitch"),
                text("Каждый трек играет быстрее или медленнее, а тон идёт за скоростью, как у пластинки: slowed "
                                + "медленнее и ниже, speed up быстрее и выше. На ходу, без отдельных файлов. Треки, в "
                                + "названии которых уже есть slowed, sped up или nightcore, остаются как есть: второй "
                                + "раз менять их не нужно. Тексты песен и полоса прогресса остаются верными: время идёт "
                                + "по самому треку. То же включает кнопка в полном плеере.",
                        "Every track plays faster or slower and the pitch follows the speed, like a record: slowed is "
                                + "lower and slower, speed up is higher and faster. On the fly, with no extra files. "
                                + "Tracks whose title already says slowed, sped up or nightcore are left as they are: "
                                + "there is no point in changing them twice. The lyrics and the progress bar stay "
                                + "right: time runs by the track itself. The button in the full-screen player does the same."),
                v -> chooseSpeed());
        speedRow.icon("playback").brief(text("Обычная, slowed или speed up.", "Normal, slowed or speed up."));
        speedRow.value(TrackVersions.speed().label());
        list.addView(speedRow);
        list.addView(ui.createChoiceRow(text("Насколько замедлять", "How much to slow"), null,
                TrackVersions.SLOWED_PERCENT, TrackVersions.DEFAULT_SLOWED_PERCENT, TrackVersions.SLOWED_PERCENTS,
                new String[]{"90 %", text("85 % (обычный slowed)", "85 % (usual slowed)"), "80 %", "75 %", "70 %", "65 %"},
                SlowedPlayback::onSettingChanged));
        list.addView(ui.createChoiceRow(text("Насколько ускорять", "How much to speed up"), null,
                TrackVersions.SPEED_UP_PERCENT, TrackVersions.DEFAULT_SPEED_UP_PERCENT, TrackVersions.SPEED_UP_PERCENTS,
                new String[]{"110 %", "115 %", "120 %", text("125 % (обычный speed up)", "125 % (usual speed up)"), "130 %", "140 %"},
                SlowedPlayback::onSettingChanged));
        list.addView(strengthRow("Reverb",
                text("Эхо большого зала: голос и инструменты тянутся дольше. Сочетается с любой скоростью и с басом.",
                        "The echo of a big hall: voice and instruments ring longer. Combines with any speed and with the bass."),
                "reverb", TrackVersions::reverb, TrackVersions::setReverb));
        list.addView(strengthRow("Bass boost",
                text("Усиливает низкие частоты. Сочетается с любой скоростью и с реверберацией.",
                        "Lifts the low frequencies. Combines with any speed and with the reverb."),
                "eq", TrackVersions::bass, TrackVersions::setBass));
    }

    private ActionRow speedRow;

    private void chooseSpeed() {
        VersionSpeed[] speeds = VersionSpeed.values();
        String[] labels = new String[speeds.length];
        for (int i = 0; i < speeds.length; i++) labels[i] = speeds[i].label();
        new ArsoundDialog(ui.activity)
                .setTitle(text("Скорость и тон", "Speed and pitch"))
                .setSingleChoiceItems(labels, TrackVersions.speed().ordinal(), (dialog, which) -> {
                    TrackVersions.setSpeed(speeds[which]);
                    speedRow.value(speeds[which].label());
                    SlowedPlayback.onSettingChanged();
                    dialog.dismiss();
                })
                .show();
    }

    /** A row that picks off, weak, medium or strong for an effect. */
    private ActionRow strengthRow(String name, String description, String icon, Supplier<VersionStrength> read,
                                  Consumer<VersionStrength> write) {
        ActionRow[] row = new ActionRow[1];
        VersionStrength[] strengths = VersionStrength.values();
        String[] labels = new String[strengths.length];
        for (int i = 0; i < strengths.length; i++) labels[i] = strengths[i].label();
        row[0] = ui.createActionRow(name, description, v -> new ArsoundDialog(ui.activity)
                .setTitle(name)
                .setSingleChoiceItems(labels, read.get().ordinal(), (dialog, which) -> {
                    write.accept(strengths[which]);
                    row[0].value(strengths[which].label());
                    AudioEqualizer.onVersionsChanged();
                    dialog.dismiss();
                })
                .show());
        row[0].icon(icon);
        row[0].value(read.get().label());
        return row[0];
    }
}
