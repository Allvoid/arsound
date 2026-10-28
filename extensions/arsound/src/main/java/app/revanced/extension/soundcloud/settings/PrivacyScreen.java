package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.widget.LinearLayout;
import android.widget.Toast;

/** Battery saving and telemetry. */
final class PrivacyScreen extends SectionScreen {
    static final String ID = "privacy";

    PrivacyScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Батарея и конфиденциальность", "Battery and privacy");
    }

    @Override
    String description() {
        return text("Экономия батареи, телеметрия", "Battery saving, telemetry");
    }

    @Override
    String summary() {
        return text("Экономия батареи: ", "Battery saving: ") + onOff(BoolSettings.POWER_SAVING.get())
                + text(" · телеметрия: ", " · telemetry: ") + onOff(BoolSettings.TELEMETRY_ENABLED.get());
    }

    @Override
    String iconKey() {
        return "privacy";
    }

    private static String onOff(boolean on) {
        return on ? text("вкл", "on") : text("выкл", "off");
    }

    @Override
    void fill(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Батарея", "Battery")));
        list.addView(ui.createToggleRow(
                text("Экономия батареи", "Battery saving"),
                text("Приложение реже выходит в сеть в фоне: новые сообщения проверяются раз в 5 минут вместо "
                                + "каждых 30 секунд, встроенные сервисы аналитики не отправляют отчёты. "
                                + "Применится после перезапуска.",
                        "The app goes online less in the background: new messages are checked every 5 minutes "
                                + "instead of every 30 seconds, built-in analytics services send no reports. Applies after a restart."),
                BoolSettings.POWER_SAVING
        ).icon("battery").brief(text("Реже выходит в сеть в фоне и не шлёт отчёты аналитики.",
                "Goes online less in the background and sends no analytics.")));

        list.addView(ui.createSubHeading(text("Приватность", "Privacy")));
        list.addView(ui.createToggleRow(
                text("Телеметрия", "Telemetry"),
                text("Отправка статистики использования в SoundCloud. Применится после перезапуска.",
                        "Sends usage statistics to SoundCloud. Applies after a restart."),
                BoolSettings.TELEMETRY_ENABLED,
                (button, checked) -> {
                    Toast.makeText(activity,
                            text("Перезапустите SoundCloud, чтобы применить", "Restart SoundCloud to apply"),
                            Toast.LENGTH_SHORT).show();
                }
        ).icon("privacy"));
    }
}
