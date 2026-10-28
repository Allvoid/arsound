package app.revanced.extension.soundcloud.network;

import android.content.Context;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.shared.Toasts;

/** The toast that says SoundCloud was switched off, and why. */
final class BlockNotice {
    private BlockNotice() {
    }

    /**
     * @param country The country code of the IP address, or null when the check failed.
     * @param russian Whether to write in Russian.
     */
    static String message(String country, boolean russian) {
        if (country == null) {
            return russian ? "Arsound: не удалось проверить страну IP — SoundCloud отключён, играют скачанные треки"
                    : "Arsound: could not check the IP country, SoundCloud is off, downloaded tracks still play";
        }
        return russian ? "Arsound: " + IpCountry.describe(country, true) + " — SoundCloud отключён, играют скачанные треки"
                : "Arsound: " + IpCountry.describe(country, false) + ", SoundCloud is off, downloaded tracks still play";
    }

    /** @param country The country code of the IP address, or null when the check failed. */
    static void show(String country) {
        Context context = Utils.getContext();
        if (context == null) return;
        Toasts.showLongOnMainThread(context, message(country, Lang.russian()));
    }
}
