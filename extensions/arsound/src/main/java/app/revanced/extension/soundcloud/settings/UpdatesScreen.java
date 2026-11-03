package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.Toast;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * Update checks, links to the project, the backup of the settings to a file ({@link SettingsTransferRows}) and the reset
 * of SoundCloud's data.
 */
final class UpdatesScreen extends SectionScreen {
    static final String ID = "updates";

    /** Export and import of the settings; the file picker answers to this screen. */
    private final SettingsTransferRows transfer;

    UpdatesScreen(SettingsUi ui) {
        super(ui);
        transfer = new SettingsTransferRows(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Обновления и данные", "Updates and data");
    }

    @Override
    String description() {
        return text("Версия " + UpdateChecker.VERSION + ", проверка обновлений, резервная копия настроек, сброс данных SoundCloud",
                "Version " + UpdateChecker.VERSION + ", update check, settings backup, SoundCloud data reset");
    }

    @Override
    String keywords() {
        return text("экспорт импорт настроек резервная копия бэкап перенос на новый телефон переустановка восстановить",
                "export import settings backup restore transfer new phone reinstall");
    }

    @Override
    String summary() {
        return text("Версия ", "Version ") + UpdateChecker.VERSION;
    }

    @Override
    String iconKey() {
        return "update";
    }

    @Override
    void fill(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Версия", "Version")));
        list.addView(ui.createActionRow(
                text("Проверить обновления", "Check for updates"),
                text("Версия " + UpdateChecker.VERSION, "Version " + UpdateChecker.VERSION),
                v -> UpdateChecker.check((release, failed) -> {
                    if (activity.isFinishing()) return;
                    if (release != null) {
                        UpdateChecker.showUpdateSheet(activity, release);
                    } else {
                        Toast.makeText(activity, failed
                                        ? text("Не удалось проверить обновления", "Could not check for updates")
                                        : text("У вас последняя версия", "You have the latest version"),
                                Toast.LENGTH_SHORT).show();
                    }
                })
        ).icon("update").chevron());
        list.addView(ui.createToggleRow(
                text("Проверять при запуске", "Check on launch"),
                text("При запуске приложение смотрит на GitHub, вышла ли новая версия Arsound.",
                        "On launch the app checks GitHub for a new Arsound version."),
                BoolSettings.UPDATE_CHECK
        ).icon("check"));

        list.addView(ui.createSubHeading(text("Сообщество", "Community")));
        list.addView(ui.createActionRow(
                text("Arsound в Telegram", "Arsound on Telegram"),
                text("Готовый APK каждой версии и новости — ", "The ready APK of every version and news: ")
                        + UpdateChecker.TELEGRAM_URL.replace("https://", ""),
                v -> UpdateChecker.openUrl(activity, UpdateChecker.TELEGRAM_URL)
        ).icon("link").chevron());
        list.addView(ui.createActionRow(
                text("Arsound на GitHub", "Arsound on GitHub"),
                UpdateChecker.REPOSITORY_URL.replace("https://", ""),
                v -> UpdateChecker.openUrl(activity, UpdateChecker.REPOSITORY_URL)
        ).icon("link").chevron());

        transfer.addTo(list);

        list.addView(ui.createSubHeading(text("Сброс", "Reset")));
        list.addView(ui.createActionRow(
                text("Сбросить данные SoundCloud", "Reset SoundCloud data"),
                text("Удаляет кэш, базу треков и настройки SoundCloud, как «Очистить данные» в Android. Помогает, "
                                + "если трек пишет «Недоступно в вашей стране», хотя в оригинале играет. Нужен интернет. "
                                + "Вход, настройки Arsound, скачанные треки и порядок сохранятся.",
                        "Removes the SoundCloud cache, track database and settings, like \"Clear data\" in Android. Helps when "
                                + "a track says it is not available in your country. Needs a connection. Login, Arsound settings, "
                                + "downloaded tracks and order are kept."),
                this::confirmReset
        ).icon("reset").danger().brief(text("Как «Очистить данные» в Android, вход и настройки Arsound сохранятся.",
                "Like \"Clear data\" in Android; login and Arsound settings are kept.")));
    }

    @Override
    void onActivityResult(int requestCode, int resultCode, Intent data) {
        transfer.onActivityResult(requestCode, resultCode, data);
    }

    /** Set while the data is being removed: a second tap does not start another reset. */
    private static boolean resetting;

    private void confirmReset(android.view.View row) {
        new ArsoundDialog(activity)
                .setTitle(text("Сбросить данные?", "Reset data?"))
                .setMessage(text("SoundCloud перезапустится и заново загрузит библиотеку с сервера — нужен рабочий интернет. "
                                + "Без него плейлисты не откроются, пока сеть не появится. Вход, настройки Arsound, "
                                + "скачанные треки, порядок плейлистов и импортированная музыка останутся.",
                        "SoundCloud restarts and downloads your library from the server again, so a working connection "
                                + "is needed. Without it playlists stay empty until the network is back. Login, Arsound "
                                + "settings, downloaded tracks, playlist order and imported music are kept."))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(text("Сбросить", "Reset"), (dialog, which) -> {
                    if (resetting) return;
                    resetting = true;
                    // A big library takes seconds to delete: off the main thread, which froze until Android offered
                    // to close the app. The app restarts when it is done.
                    row.setEnabled(false);
                    Toast.makeText(activity, text("Сбрасываю данные…", "Resetting data…"), Toast.LENGTH_LONG).show();
                    android.content.Context context = activity.getApplicationContext();
                    Settings.flush();
                    Utils.runOnBackgroundThread(() -> {
                        try {
                            DataReset.resetKeepingLogin(context);
                        } finally {
                            Utils.runOnMainThread(() -> Utils.restartApp(context));
                        }
                    });
                })
                .show();
    }
}
