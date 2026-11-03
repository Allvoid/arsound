package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.Toast;

import app.revanced.extension.soundcloud.recommendations.WaveMemory;
import app.revanced.extension.soundcloud.recommendations.WaveSource;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The settings of "My Wave": whether its tab stands in the bottom bar, where the tracks come from and what the wave
 * remembers. Starting the wave and its tracks are on the tab itself ({@code recommendations.WavePage}); this screen is
 * opened by the gear there and from the settings.
 */
final class WaveScreen extends SectionScreen {
    static final String ID = "wave";

    WaveScreen(SettingsUi ui) {
        super(ui);
    }

    /** The intent that opens this screen from anywhere in the app. */
    public static Intent intent(Context context) {
        return new Intent(context, ReVancedSettingsActivity.class).putExtra(ReVancedSettingsActivity.EXTRA_SCREEN, ID);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Моя волна", "My Wave");
    }

    @Override
    String description() {
        return text("Вкладка внизу экрана, откуда брать треки, память волны",
                "The tab of the bottom bar, where the tracks come from, what the wave remembers");
    }

    @Override
    String iconKey() {
        return "recs";
    }

    @Override
    String keywords() {
        return text("волна вкладка рекомендации бесконечно лайки last.fm deezer похожие радио",
                "wave tab recommendations endless likes last.fm deezer related radio");
    }

    @Override
    String summary() {
        return BoolSettings.HIDE_TAB_WAVE.get() ? text("Вкладка выключена", "Tab off") : text("Вкладка включена", "Tab on");
    }

    @Override
    void fill(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Вкладка", "Tab")));
        list.addView(ui.createToggleRow(
                text("Вкладка «Моя волна»", "\"My Wave\" tab"),
                text("Отдельная вкладка внизу экрана, сразу после «Главной». На ней волна включается одной кнопкой, "
                                + "там же источники и треки волны. Эта же галочка стоит в «Нижней панели». "
                                + "После переключения приложение перезапустится.",
                        "A tab of its own in the bottom bar, right after Home. There the wave starts with one button, "
                                + "and the sources and the wave's tracks are there too. The same tick is on the \"Bottom "
                                + "bar\" screen. The app restarts after a switch."),
                !BoolSettings.HIDE_TAB_WAVE.get(),
                (button, checked) -> {
                    BoolSettings.HIDE_TAB_WAVE.set(!checked);
                    ArsoundTheme.restartApp(activity);
                }
        ).icon("recs").brief(text("Вкладка внизу, после «Главной».", "A tab in the bottom bar, after Home.")));

        list.addView(ui.createSubHeading(text("Откуда брать треки", "Where the tracks come from")));
        String[] icons = {"recs", "stats", "music"};
        for (WaveSource source : WaveSource.values()) {
            ToggleRow[] row = new ToggleRow[1];
            row[0] = ui.createToggleRow(source.label(), source.note(), source.isOn(), (button, checked) -> {
                if (!checked && WaveSource.onCount() <= 1) {
                    row[0].setChecked(true);
                    Toast.makeText(activity, text("Хотя бы один источник остаётся включённым",
                            "At least one source stays on"), Toast.LENGTH_SHORT).show();
                    return;
                }
                source.set(checked);
            });
            row[0].icon(icons[source.ordinal() % icons.length]);
            list.addView(row[0]);
        }

        list.addView(ui.createSubHeading(text("Память волны", "What the wave remembers")));
        ActionRow[] forget = new ActionRow[1];
        forget[0] = ui.createActionRow(text("Забыть пропущенные и дослушанные", "Forget skipped and heard tracks"),
                remembered(),
                v -> {
                    WaveMemory.forget();
                    forget[0].description().setText(SettingText.split(remembered()).summary);
                    Toast.makeText(activity, text("Волна начнёт с чистого листа", "The wave starts afresh"),
                            Toast.LENGTH_SHORT).show();
                });
        forget[0].icon("trash");
        list.addView(forget[0]);
    }

    /** What the wave remembers now, for the row that forgets it. */
    private static String remembered() {
        return text("Запомнено: пропущенных " + WaveMemory.skippedCount() + ", дослушанных " + WaveMemory.heardCount()
                        + ". Образцы волны — ваши лайки и треки, дослушанные хотя бы 45 секунд; трек, пропущенный в первые "
                        + "15 секунд, отдыхает три дня, «Не нравится» не предлагается никогда. Сброс начнёт с чистого листа, лайки и "
                        + "«Не нравится» не меняются.",
                "Remembered: " + WaveMemory.skippedCount() + " skipped, " + WaveMemory.heardCount()
                        + " heard. The wave's seeds are your likes and the tracks heard at least 45 seconds; a track skipped "
                        + "in the first 15 seconds rests three days, \"Not for me\" never comes again. Forgetting starts afresh; "
                        + "likes and \"Not for me\" stay as they are.");
    }
}
