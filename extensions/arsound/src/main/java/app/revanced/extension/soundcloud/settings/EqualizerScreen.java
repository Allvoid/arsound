package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.view.View;
import android.widget.LinearLayout;

import java.util.Locale;
import java.util.function.IntConsumer;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.player.AudioEqualizer;
import app.revanced.extension.soundcloud.shared.Lang;

/** The preamp, and the equalizer of the player (ten bands, in software) with presets and a curve to drag. */
final class EqualizerScreen extends SectionScreen {
    static final String ID = "equalizer";

    EqualizerScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Эквалайзер", "Equalizer");
    }

    @Override
    String description() {
        return text("Преамп и громкость низких, средних и высоких частот", "Preamp and levels of low, middle and high frequencies");
    }

    @Override
    boolean searched() {
        return false;
    }

    /** A preamp step: half a decibel. */
    private static final int PREAMP_STEP = 50;

    /** The bands and presets of the phone do not change while the app runs, so they are read once. */
    private static volatile AudioEqualizer.Info equalizerInfo;

    @Override
    String iconKey() {
        return "eq";
    }

    @Override
    String summary() {
        return AudioEqualizer.isEnabled() ? text("Включён", "On") : text("Выключен", "Off");
    }

    @Override
    void fill(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Эквалайзер", "Equalizer")));
        LinearLayout options = ui.createColumn();
        list.addView(ui.createToggleRow(
                text("Включить эквалайзер", "Turn on the equalizer"),
                text("Меняет звук всего, что играет в SoundCloud, сразу, без перезапуска. Работает на любом телефоне: звук обрабатывает сам плеер, системный эквалайзер не нужен.",
                        "Changes the sound of everything SoundCloud plays, at once, without a restart. Works on every phone: the player shapes the sound itself, Android's equalizer is not needed."),
                AudioEqualizer.isEnabled(),
                (button, checked) -> {
                    AudioEqualizer.setEnabled(checked);
                    if (checked) {
                        options.setAlpha(0f);
                        options.setVisibility(View.VISIBLE);
                        options.animate().alpha(1f).setDuration(250).start();
                    } else {
                        options.setVisibility(View.GONE);
                    }
                }
        ).icon("eq").brief(text("Меняет звук всего, что играет, на любом телефоне, без системного эквалайзера.",
                "Changes the sound of everything that plays, on every phone, without Android's equalizer.")));
        options.setVisibility(AudioEqualizer.isEnabled() ? View.VISIBLE : View.GONE);
        list.addView(options);
        list.addView(ui.createSubHeading(text("Громкость", "Volume")));
        addPreampRow(list);

        AudioEqualizer.Info known = equalizerInfo;
        if (known != null) {
            showEqualizerPanel(known, options);
            return;
        }
        // Reading the effect is a series of calls to the audio service, so the screen opens first.
        Utils.runOnBackgroundThread(() -> {
            AudioEqualizer.Info info = AudioEqualizer.readInfo();
            if (isComplete(info)) equalizerInfo = info;
            Utils.runOnMainThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                showEqualizerPanel(info, options);
            });
        });
    }

    private static String preampText(int millibels) {
        String value = String.format(Locale.US, "%+.1f", millibels / 100f);
        return (millibels == 0 ? "0" : value).replace('.', Lang.russian() ? ',' : '.') + text(" дБ", " dB");
    }

    /**
     * The overall volume: louder or quieter without touching the curve, and with the equalizer turned off too.
     * Holding the row sets it back to zero.
     */
    private void addPreampRow(LinearLayout list) {
        int min = AudioEqualizer.PREAMP_MIN;
        int max = AudioEqualizer.PREAMP_MAX;
        IntConsumer[] move = new IntConsumer[1];
        SettingRow row = ui.createSliderRow(text("Преамп", "Preamp"),
                text("Общая громкость всего, что играет, — тише или громче, не меняя эквалайзер. Работает и с "
                                + "выключенным эквалайзером. При усилении громкие места не хрипят: их придерживает ограничитель. "
                                + "Удерживайте строку, чтобы вернуть 0.",
                        "The overall volume of everything that plays, quieter or louder, without changing the equalizer. Works "
                                + "with the equalizer turned off too. When boosting, a limiter keeps loud parts from distorting. "
                                + "Hold the row to set it back to 0."),
                (max - min) / PREAMP_STEP, (AudioEqualizer.preamp() - min) / PREAMP_STEP,
                progress -> preampText(min + progress * PREAMP_STEP),
                progress -> AudioEqualizer.setPreamp(min + progress * PREAMP_STEP),
                null, move);
        row.icon("volume").brief(text("Общая громкость, тише или громче. Удерживайте, чтобы вернуть 0.",
                "The overall volume, quieter or louder. Hold to set back to 0."));
        row.setOnLongClickListener(v -> {
            move[0].accept(-min / PREAMP_STEP);
            AudioEqualizer.setPreamp(0);
            return true;
        });
        list.addView(row);
    }

    /** Whether the read got every preset; a failed read is not kept, the next visit tries again. */
    private static boolean isComplete(AudioEqualizer.Info info) {
        if (info.centerFrequenciesHz.length == 0 || info.maxLevel <= info.minLevel) return false;
        if (info.presets.length != info.presetLevels.length) return false;
        for (short[] levels : info.presetLevels) if (levels == null) return false;
        return true;
    }

    private void showEqualizerPanel(AudioEqualizer.Info info, LinearLayout options) {
        if (info.centerFrequenciesHz.length == 0) {
            options.addView(ui.createText("Body.Secondary", text("Эквалайзер недоступен на этом телефоне.", "The equalizer is not available on this phone.")));
            return;
        }
        new EqualizerPanel(ui, info, options).build();
    }
}
