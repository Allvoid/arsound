package app.revanced.extension.soundcloud.debug;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Lang;

/** "The logs are saved": where the file is, and a button that sends it. */
final class SavedLogsDialog {
    private SavedLogsDialog() {
    }

    /**
     * @param uri The saved file, or null if it cannot be shared (then only the path is shown).
     */
    static void show(Activity activity, String path, Uri uri) {
        ArsoundDialog dialog = new ArsoundDialog(activity)
                .setTitle(Lang.text("Журналы сохранены", "The logs are saved"))
                .setMessage(Lang.text("Файл: ", "File: ") + path + "\n\n"
                        + Lang.text("Отправьте этот файл разработчику.", "Send this file to the developer."));
        if (uri != null) {
            dialog.setNegativeButton(Lang.text("Закрыть", "Close"), null);
            dialog.setPositiveButton(Lang.text("Отправить", "Send"), (d, which) -> {
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                // Lets the share sheet itself read the file, so it shows the file name instead of its number.
                send.setClipData(ClipData.newUri(activity.getContentResolver(), path, uri));
                activity.startActivity(Intent.createChooser(send, Lang.text("Отправить журналы", "Send the logs")));
            });
        } else {
            dialog.setPositiveButton(android.R.string.ok, null);
        }
        dialog.show();
    }

    /** The settings screen has nothing to save yet: the playback log starts after a restart, or is turned off. */
    static void showNoLogs(Activity activity) {
        new ArsoundDialog(activity)
                .setTitle(Lang.text("Журналов пока нет", "No logs yet"))
                .setMessage(PlaybackTimeline.isEnabled()
                        ? Lang.text("Журнал воспроизведения начнёт писаться после перезапуска приложения. "
                                + "Перезапустите SoundCloud, повторите проблему и нажмите кнопку снова.",
                        "The playback log starts after the app restarts. Restart SoundCloud, make the "
                                + "problem happen again and tap the button once more.")
                        : Lang.text("Журнал воспроизведения выключен ниже, в настройках для разработчика.",
                        "The playback log is turned off below, in the developer options."))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
