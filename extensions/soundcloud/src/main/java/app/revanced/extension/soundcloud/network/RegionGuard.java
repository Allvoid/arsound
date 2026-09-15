package app.revanced.extension.soundcloud.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Stops all requests to SoundCloud while the app goes online from a Russian IP address.
 * <p>
 * The country is checked through a neutral service (Cloudflare trace), over the same route the
 * app uses, including a VPN. Until the check finishes, requests wait for it instead of leaking the
 * address. The result is kept until the network changes.
 */
public final class RegionGuard {
    private static final String BLOCKED_COUNTRY = "RU";
    private static final int CHECK_TIMEOUT_MS = 6_000;

    private static volatile String country;
    private static volatile boolean listening;
    private static volatile boolean toastShown;

    private RegionGuard() {
    }

    public static String lastCountry() {
        return country;
    }

    /** @return True if the request to this host must not be sent. */
    public static boolean shouldBlock(String host) {
        if (!Settings.isRegionGuardEnabled() || host == null) return false;
        if (!isSoundCloudHost(host)) return false;

        listenForNetworkChanges();
        String current = country;
        if (current == null) current = check();
        boolean blocked = current == null || BLOCKED_COUNTRY.equals(current);
        if (blocked) showBlockedToast(current);
        return blocked;
    }

    public static void throwIfBlocked(String host) throws IOException {
        if (shouldBlock(host)) {
            throw new IOException("Arsound: requests to SoundCloud are blocked from a Russian IP address");
        }
    }

    private static boolean isSoundCloudHost(String host) {
        host = host.toLowerCase(Locale.US);
        return host.endsWith("soundcloud.com") || host.endsWith("sndcdn.com") || host.endsWith("soundcloud.cloud")
                || host.endsWith("snd.sc");
    }

    /** Checks the country once for all waiting requests. Null means the check failed. */
    private static synchronized String check() {
        if (country != null) return country;
        try {
            HttpURLConnection connection = (HttpURLConnection)
                    new URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection();
            connection.setConnectTimeout(CHECK_TIMEOUT_MS);
            connection.setReadTimeout(CHECK_TIMEOUT_MS);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("loc=")) {
                        country = line.substring(4).trim().toUpperCase(Locale.US);
                        Logger.printInfo(() -> "Region guard: country " + country);
                        return country;
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Region guard: country check failed: " + ex);
        }
        return null;
    }

    private static void listenForNetworkChanges() {
        if (listening) return;
        Context context = Utils.getContext();
        if (context == null) return;
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    country = null;
                    toastShown = false;
                }

                @Override
                public void onLost(Network network) {
                    country = null;
                }
            });
            listening = true;
        } catch (Exception ex) {
            Logger.printException(() -> "Region guard: could not watch network changes", ex);
        }
    }

    private static void showBlockedToast(String current) {
        if (toastShown) return;
        toastShown = true;
        Context context = Utils.getContext();
        if (context == null) return;
        boolean russian = "ru".equals(Locale.getDefault().getLanguage());
        String message = current == null
                ? (russian ? "Arsound: не удалось проверить страну IP — SoundCloud отключён, играют скачанные треки"
                : "Arsound: could not check the IP country, SoundCloud is off, downloaded tracks still play")
                : (russian ? "Arsound: российский IP — SoundCloud отключён, играют скачанные треки"
                : "Arsound: Russian IP, SoundCloud is off, downloaded tracks still play");
        Utils.runOnMainThread(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
