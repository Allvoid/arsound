package app.revanced.extension.soundcloud.branding;

import android.content.Context;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Which mark the app shows outside its own screens: the Arsound letter, or SoundCloud's cloud when the user chose one
 * of SoundCloud's app icons. The launcher icon alone was not the whole look: the small icon of the notifications
 * stayed the letter, and it is what the status bar shows, and what a phone with a "live island" beside the clock
 * (HyperOS, One UI's Now Bar, vivo, the live updates of Android 16) shows for the playing track.
 * <p>
 * The cloud is SoundCloud's own notification icon, copied by the branding patch under a name of its own before the
 * letter replaces it ({@code arsound_sc_notification}).
 */
@SuppressWarnings("unused")
public final class BrandMark {
    /** The prefix of the ids of SoundCloud's icons ({@link SoundCloudIconList}). */
    private static final String SOUNDCLOUD_PREFIX = "sc_";
    private static final String CLOUD_ICON = "arsound_sc_notification";

    /** Whether one of SoundCloud's icons is chosen; null until asked. */
    private static volatile Boolean soundCloud;
    /** The resource of the cloud; 0 until looked up, -1 when the app has none. */
    private static volatile int cloudIcon;

    private BrandMark() {
    }

    /** Whether the user chose one of SoundCloud's own app icons. */
    public static boolean isSoundCloud(Context context) {
        Boolean known = soundCloud;
        if (known != null) return known;
        boolean chosen = isSoundCloudIcon(AppIcons.current(context));
        soundCloud = chosen;
        return chosen;
    }

    static boolean isSoundCloudIcon(String id) {
        return id != null && id.startsWith(SOUNDCLOUD_PREFIX);
    }

    /** Called when the user picks an icon: the next notification is built with its mark. */
    static void onIconChanged(String id) {
        soundCloud = isSoundCloudIcon(id);
    }

    /**
     * Injection point: wherever SoundCloud takes the small icon of a notification, and the logo of its widget.
     *
     * @param icon The resource SoundCloud would use, which the branding made the Arsound letter.
     * @return The cloud when one of SoundCloud's icons is chosen, else the icon as given.
     */
    public static int mark(int icon) {
        try {
            Context context = Utils.peekContext();
            if (context == null || !isSoundCloud(context)) return icon;
            int cloud = cloudIcon;
            if (cloud == 0) {
                cloud = context.getResources().getIdentifier(CLOUD_ICON, "drawable", context.getPackageName());
                if (cloud == 0) cloud = -1;
                cloudIcon = cloud;
            }
            return cloud > 0 ? cloud : icon;
        } catch (Throwable ex) {
            // A hook inside the making of a notification: the icon SoundCloud asked for is always a good answer.
            Logger.printException(() -> "Brand mark: could not choose the notification icon", ex);
            return icon;
        }
    }
}
