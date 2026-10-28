package app.revanced.extension.soundcloud.permissions;

import static app.revanced.extension.soundcloud.permissions.WelcomeLanguage.text;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.soundcloud.download.MusicAccess;

/** One permission of the greeting: what it is for, and what stops working without it. */
final class WelcomeItem {
    private static final String NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    final String permission;
    final String title;
    final String purpose;
    final String withoutIt;

    private WelcomeItem(String permission, String title, String purpose, String withoutIt) {
        this.permission = permission;
        this.title = title;
        this.purpose = purpose;
        this.withoutIt = withoutIt;
    }

    boolean isGranted(Context context) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** The permissions the greeting tells about, with the texts in the language of the greeting now. */
    static List<WelcomeItem> all() {
        List<WelcomeItem> items = new ArrayList<>();
        // Before Android 13 notifications need no permission.
        if (Build.VERSION.SDK_INT >= 33) {
            items.add(new WelcomeItem(NOTIFICATIONS,
                    text("Уведомления", "Notifications"),
                    text("плеер в шторке и на экране блокировки, ход скачивания треков.",
                            "the player in the notification shade and on the lock screen, download progress."),
                    text("музыка играет, но управлять ею из шторки нельзя, а скачивание идёт незаметно.",
                            "music plays, but cannot be controlled from the shade, and downloads run unseen.")));
        }
        items.add(new WelcomeItem(MusicAccess.permission(),
                text("Музыка и аудио", "Music and audio"),
                text("слушать без интернета треки, скачанные раньше, если Arsound удаляли и ставили заново. "
                                + "Обычные обновления на это не влияют.",
                        "play offline the tracks you downloaded earlier, if Arsound was removed and installed again. "
                                + "Regular updates do not affect this."),
                text("всё работает, но после удаления и новой установки старые скачанные треки будут играть только через интернет.",
                        "everything works, but after removing and installing again, older downloads only play over the network.")));
        return items;
    }
}
