package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.revanced.extension.shared.debug.LogFile;
import app.revanced.extension.shared.settings.BaseSettings;
import app.revanced.extension.soundcloud.debug.LogExport;
import app.revanced.extension.soundcloud.debug.PlaybackTimeline;

/** Saving the logs, and the testing and debugging tools behind the developer switch. */
final class DeveloperScreen extends SectionScreen {
    static final String ID = "developer";

    DeveloperScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Для разработчика", "Developer");
    }

    @Override
    String description() {
        return text("Инструменты для проверки и отладки", "Testing and debugging tools");
    }

    @Override
    String iconKey() {
        return "developer";
    }

    @Override
    String summary() {
        return Settings.isDeveloperModeEnabled() ? text("Настройки для разработчика включены", "Developer options on")
                : text("Журналы для разработчика", "Logs for the developer");
    }

    @Override
    void fill(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Журналы", "Logs")));
        // First and outside the developer options: a user who hit a bug only needs this button.
        list.addView(ui.createActionRow(
                text("Сохранить журналы", "Save the logs"),
                text("Сохраняет журналы за последние два дня и сведения о сети и телефоне одним файлом в "
                                + "«Загрузки». Если что-то работает не так — нажмите и отправьте файл разработчику.",
                        "Saves the logs of the last two days and the state of the network and the phone as one "
                                + "file in Downloads. If something goes wrong, tap here and send the file to the developer."),
                v -> LogExport.save(activity, true)
        ).icon("log").chevron().brief(text("Один файл в «Загрузки» — отправьте его разработчику.",
                "One file in Downloads to send to the developer.")));

        list.addView(ui.createSubHeading(text("Для разработчика", "Developer")));
        SubRows developerOptions = ui.createSubRows();
        list.addView(ui.createToggleRow(
                text("Настройки для разработчика", "Developer options"),
                text("Инструменты для проверки и отладки. Обычно не нужны.",
                        "Testing and debugging tools. Usually not needed."),
                BoolSettings.DEVELOPER_MODE,
                developerOptions
        ).icon("developer"));
        list.addView(developerOptions);
        addDeveloperOptions(developerOptions);
    }

    private static final int[] NETWORK_DELAYS = {0, 3, 10, 20, 40};

    private void addDeveloperOptions(LinearLayout container) {
        TextView[] delayDescription = new TextView[1];
        ActionRow delayRow = ui.createActionRow(
                text("Имитация плохой сети", "Simulate a poor connection"),
                networkDelayDescription(),
                v -> {
                    int current = Settings.isDeveloperModeEnabled()
                            ? Settings.getDeveloperNetworkDelaySeconds() : 0;
                    int next = NETWORK_DELAYS[0];
                    for (int i = 0; i < NETWORK_DELAYS.length; i++) {
                        if (NETWORK_DELAYS[i] == current) {
                            next = NETWORK_DELAYS[(i + 1) % NETWORK_DELAYS.length];
                            break;
                        }
                    }
                    Settings.setDeveloperNetworkDelaySeconds(next);
                    delayDescription[0].setText(networkDelayDescription());
                }
        );
        delayDescription[0] = delayRow.description();
        container.addView(delayRow);

        container.addView(ui.createToggleRow(
                text("Журнал воспроизведения", "Playback log"),
                text("Записывает каждый старт и переключение трека: сколько он ждал и почему, состояние сети, ошибки. "
                                + "Нужен, чтобы разобраться в поломке воспроизведения. Хранится два дня в "
                                + "Android/data/<пакет>/files/playback-log. Применится после перезапуска.",
                        "Writes every track start and switch: how long it waited and why, the network, errors. Helps find "
                                + "out what broke playback. Kept for two days in Android/data/<package>/files/playback-log. "
                                + "Applies after a restart."),
                PlaybackTimeline.isEnabled(),
                (button, checked) -> Settings.putBoolean(PlaybackTimeline.ENABLED, checked)
        ));

        addLogOptions(container);
    }

    /**
     * The rare bugs of this mod show up once in a few days, so logcat is of no use: it needs a computer
     * and is wiped on reboot. With this on, the log is kept in a file that can be saved and read later.
     */
    private void addLogOptions(LinearLayout container) {
        TextView[] logDescription = new TextView[1];

        container.addView(ui.createToggleRow(
                text("Журнал в файл", "Write the log to a file"),
                text("Пишет подробный журнал работы мода в файл внутри приложения. Нужен, чтобы поймать редкую "
                                + "поломку: включите и пользуйтесь как обычно, а когда баг повторится — нажмите "
                                + "«Сохранить журналы» вверху. Журнал занимает не больше 2 МБ: самые старые записи "
                                + "затираются новыми.",
                        "Writes a detailed log of the mod into a file inside the app. Use it to catch a rare bug: "
                                + "turn it on, keep using the app, and tap \"Save the logs\" at the top once the bug "
                                + "shows up again. The log never grows past 2 MB: the oldest lines are dropped."),
                Settings.isFileLoggingEnabled(),
                (button, checked) -> {
                    Settings.setFileLoggingEnabled(checked);
                    // Without this the detailed lines are never written at all.
                    BaseSettings.DEBUG.save(checked);
                    if (logDescription[0] != null) logDescription[0].setText(clearLogDescription());
                }
        ));

        ActionRow clearRow = ui.createActionRow(
                text("Очистить журнал", "Clear the log"),
                clearLogDescription(),
                v -> {
                    LogFile.clear();
                    if (logDescription[0] != null) logDescription[0].setText(clearLogDescription());
                    Toast.makeText(activity, text("Журнал очищен", "The log is cleared"), Toast.LENGTH_SHORT).show();
                }
        );
        logDescription[0] = clearRow.description();
        container.addView(clearRow);
    }

    private String clearLogDescription() {
        long size = LogFile.size();
        String written = size == 0
                ? text("Журнал пуст.", "The log is empty.")
                : text("Записано ", "") + (size / 1024) + " " + text("КБ.", "KB written.");
        return written + " " + text("Удаляет накопленные записи. Делайте это перед тем, как ловить баг заново.",
                "Deletes what was written so far. Do this before trying to catch a bug again.");
    }

    private String networkDelayDescription() {
        int delay = Settings.getDeveloperNetworkDelaySeconds();
        String state = delay == 0
                ? text("выключено", "off")
                : text("задержка " + delay + " с на каждый запрос", delay + " s delay per request");
        return text("Нажмите, чтобы переключить: ", "Tap to change: ") + state + ". "
                + text("Замедляет все запросы SoundCloud, чтобы проверить работу на плохом интернете. "
                        + "Действует сразу.",
                "Slows down every SoundCloud request to test behavior on a poor connection. Applies immediately.");
    }
}
