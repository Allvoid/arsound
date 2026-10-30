package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import java.util.Map;

import app.revanced.extension.shared.Logger;

/**
 * Links to soundcloud.com opening in Arsound. Android hands such links to an app by itself only when the site has
 * vouched for the app's signature, and soundcloud.com vouches only for SoundCloud's own app. So a link from a browser
 * or a messenger opened the browser or the original app, never Arsound, and no app can change that by itself: the user
 * allows it once in the system's "Open by default" page of Arsound ("Add links", tick the soundcloud.com addresses).
 * This row says whether that is done and opens the page.
 */
final class SoundCloudLinks {
    private static final String MAIN_HOST = "soundcloud.com";

    private SoundCloudLinks() {
    }

    /** True when the user allowed Arsound to open soundcloud.com links. False on Android below 12, which asks each time. */
    static boolean areOpenedHere(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        try {
            DomainVerificationManager manager = context.getSystemService(DomainVerificationManager.class);
            DomainVerificationUserState state = manager == null ? null
                    : manager.getDomainVerificationUserState(context.getPackageName());
            if (state == null || !state.isLinkHandlingAllowed()) return false;
            Map<String, Integer> hosts = state.getHostToStateMap();
            Integer main = hosts.get(MAIN_HOST);
            return main != null && main != DomainVerificationUserState.DOMAIN_STATE_NONE;
        } catch (Exception ex) {
            Logger.printException(() -> "Links: could not read the link state", ex);
            return false;
        }
    }

    /** The line under the row: whether links open here, and what the tap does. */
    static String describe(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return text("Android спрашивает, чем открыть ссылку: выберите Arsound и «Всегда».",
                    "Android asks what to open a link with: choose Arsound and \"Always\".");
        }
        return areOpenedHere(context)
                ? text("Включено: ссылки soundcloud.com открываются в Arsound. Нажмите, чтобы изменить.",
                "On: soundcloud.com links open in Arsound. Tap to change.")
                : text("Выключено: ссылки открываются в браузере или в SoundCloud. Нажмите и в системном окне выберите "
                        + "«Добавить ссылки», отметьте все адреса soundcloud.com.",
                "Off: links open in the browser or in SoundCloud. Tap, then in the system page choose \"Add links\" and "
                        + "tick all the soundcloud.com addresses.");
    }

    /** Opens the system's "Open by default" page of Arsound, with a hint of what to tick there. */
    static void open(Context context) {
        Intent intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                Uri.parse("package:" + context.getPackageName()))
                : new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName()));
        try {
            context.startActivity(intent);
            Toast.makeText(context, text("«Добавить ссылки» → отметьте все soundcloud.com",
                    "\"Add links\" → tick all soundcloud.com"), Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(context, text("Откройте настройки Android → Приложения → Arsound → Открывать по умолчанию",
                            "Open Android settings → Apps → Arsound → Open by default"),
                    Toast.LENGTH_LONG).show();
        }
    }
}
