package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.LinearLayout;

import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Toasts;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The two rows that save the settings to a file and load them back (a new phone, a reinstall), with the system's own file
 * picker for both. A screen adds them with {@link #addTo} and hands them the answer of the picker through
 * {@link #onActivityResult}. The format is {@link SettingsTransfer}, what travels {@link TransferPlan}, the reading and
 * writing {@link TransferFiles}; this class is only the conversation with the user.
 * <p>
 * Import goes in three steps so nothing changes before the user agrees: the file is read and checked, a dialog says what
 * it brings and that it replaces the current settings, and only after "Import" the values are written and the app restarts
 * (the theme, the bottom bar and many switches are read once, at start).
 */
final class SettingsTransferRows {
    static final int REQUEST_EXPORT = 7201;
    static final int REQUEST_IMPORT = 7202;

    /** Set from the confirmed import until it is written: a second tap on "Import" does not start another. */
    private static final AtomicBoolean applying = new AtomicBoolean();

    private final ReVancedSettingsActivity activity;
    private final SettingsUi ui;

    SettingsTransferRows(SettingsUi ui) {
        this.ui = ui;
        this.activity = ui.activity;
    }

    /** The heading and the two rows. */
    void addTo(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Резервная копия настроек", "Settings backup")));
        list.addView(ui.createActionRow(
                text("Экспорт настроек в файл", "Export settings to a file"),
                text("Сохраняет настройки Arsound в файл — для нового телефона или переустановки. Входы в аккаунты, "
                                + "ключи и пароли в файл не попадают.",
                        "Saves your Arsound settings to a file, for a new phone or a reinstall. Sign-ins, keys and "
                                + "passwords never go into the file."),
                v -> pickDestination()
        ).icon("export").chevron().keywords(text("резервная копия бэкап сохранить перенести настройки файл",
                "backup save transfer settings file")));
        list.addView(ui.createActionRow(
                text("Импорт настроек из файла", "Import settings from a file"),
                text("Берёт настройки из сохранённого файла и заменяет ими текущие. После этого приложение перезапустится.",
                        "Loads settings from a saved file and replaces the current ones. The app restarts afterwards."),
                v -> pickSource()
        ).icon("import").chevron().keywords(text("восстановить вернуть резервная копия бэкап загрузить настройки файл",
                "restore backup load settings file")));
    }

    /**
     * The answer of the file picker.
     *
     * @return True if the request was one of ours (cancelled or not).
     */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_EXPORT && requestCode != REQUEST_IMPORT) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        if (requestCode == REQUEST_EXPORT) export(data.getData());
        else load(data.getData());
        return true;
    }

    // region Export

    private void pickDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, TransferFiles.suggestedName());
        launch(intent, REQUEST_EXPORT);
    }

    private void export(Uri destination) {
        Context context = activity.getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            try {
                Toasts.showLongOnMainThread(context, TransferTexts.exported(TransferFiles.export(context, destination)));
            } catch (TransferException ex) {
                Toasts.showLongOnMainThread(context, TransferTexts.failure(ex.reason));
            } catch (Exception ex) {
                Logger.printException(() -> "Settings export failed", ex);
                Toasts.showLongOnMainThread(context, text("Не удалось сохранить файл", "Could not save the file"));
            }
        });
    }

    // endregion

    // region Import

    private void pickSource() {
        // Any kind of file: some file managers and cloud apps do not call a .json file "application/json". What is not
        // a settings file is refused after it is read.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        launch(intent, REQUEST_IMPORT);
    }

    /** Step one: reads and checks the file off the main thread, then asks. */
    private void load(Uri source) {
        Context context = activity.getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            try {
                TransferBackup backup = TransferFiles.load(context, source);
                Utils.runOnMainThread(() -> confirm(backup));
            } catch (TransferException ex) {
                Toasts.showLongOnMainThread(context, TransferTexts.failure(ex.reason));
            } catch (Exception ex) {
                Logger.printException(() -> "Settings import: could not read the file", ex);
                Toasts.showLongOnMainThread(context, text("Не удалось открыть файл", "Could not open the file"));
            }
        });
    }

    /** Step two: says what the file brings and that it replaces the current settings. */
    private void confirm(TransferBackup backup) {
        // The screen was closed or turned while the file was read: the user starts again, nothing was changed.
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new ArsoundDialog(activity)
                .setTitle(text("Импортировать настройки?", "Import settings?"))
                .setMessage(TransferTexts.confirmation(backup))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(text("Импортировать", "Import"), (dialog, which) -> apply(backup))
                .show();
    }

    /** Step three: writes the values to the disk, then restarts. */
    private void apply(TransferBackup backup) {
        if (!applying.compareAndSet(false, true)) return;
        Context context = activity.getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            try {
                TransferChanges changes = TransferFiles.apply(context, backup);
                Utils.runOnMainThread(() -> {
                    Toasts.showLong(context, TransferTexts.imported(changes));
                    restart(context);
                });
            } catch (Exception ex) {
                applying.set(false);
                Logger.printException(() -> "Settings import: could not write the values", ex);
                Toasts.showLongOnMainThread(context, text("Не удалось записать все настройки, попробуйте ещё раз",
                        "Could not write all the settings, try again"));
            }
        });
    }

    /**
     * Restarts the way a change of theme does: the theme and its start screen come from the imported values, and the
     * new theme's colours are made before the process ends. Many settings (the bottom bar, the ad blocking) are read at start.
     */
    private void restart(Context context) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            Utils.restartApp(context);
            return;
        }
        try {
            ArsoundTheme.setCurrent(activity, ArsoundTheme.current());
        } catch (Exception ex) {
            Logger.printException(() -> "Settings import: could not set the start screen", ex);
        }
        ArsoundTheme.restartApp(activity);
    }

    // endregion

    private void launch(Intent intent, int request) {
        try {
            activity.startActivityForResult(intent, request);
        } catch (ActivityNotFoundException ex) {
            Toasts.showLong(activity, text("На телефоне нет приложения для выбора файлов", "No app on the phone picks files"));
        }
    }
}
