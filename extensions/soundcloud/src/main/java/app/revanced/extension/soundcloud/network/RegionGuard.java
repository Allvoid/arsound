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

    /** A failed country check is not repeated for this long, so a dead network does not cause a check per request. */
    private static final long FAILED_CHECK_BACKOFF_MS = 60_000;
    /** Blocked requests are answered after this pause, so SoundCloud's retry loops cannot spin the radio and CPU. */
    private static final long BLOCKED_RESPONSE_DELAY_MS = 1_500;

    private static volatile long lastFailedCheck;
    /** A blocked or failed result is re-checked in the background this often, so a new IP on the same network is noticed. */
    private static final long BLOCKED_RECHECK_MS = 30_000;
    private static volatile long checkedAt;
    private static volatile boolean recheckRunning;

    /** @return True if the request to this host must not be sent. Called on network threads. */
    public static boolean shouldBlock(String host) {
        if (!Settings.isRegionGuardEnabled() || host == null) return false;
        if (!isSoundCloudHost(host)) return false;

        listenForNetworkChanges();
        String current = country;
        if (current == null && System.currentTimeMillis() - lastFailedCheck > FAILED_CHECK_BACKOFF_MS) current = check();
        boolean blocked = current == null || BLOCKED_COUNTRY.equals(current);
        if (blocked) {
            recheckIfStale();
            showBlockedToast(current);
        }
        return blocked;
    }

    public static void throwIfBlocked(String host) throws IOException {
        if (shouldBlock(host)) {
            try {
                Thread.sleep(BLOCKED_RESPONSE_DELAY_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("Arsound: requests to SoundCloud are blocked from a Russian IP address");
        }
    }

    /**
     * Called from SoundCloud's "is the network connected" check. While requests are blocked the app
     * behaves as offline: it stops syncing and retrying, shows downloaded content and saves battery.
     * Never checks on the calling thread; an unknown country starts a background check.
     */
    public static boolean isConnected(boolean connected) {
        if (!connected || !Settings.isRegionGuardEnabled()) return connected;
        String current = country;
        if (current != null) {
            if (!BLOCKED_COUNTRY.equals(current)) return true;
            recheckIfStale();
            return false;
        }
        if (System.currentTimeMillis() - lastFailedCheck <= FAILED_CHECK_BACKOFF_MS) return false;
        Utils.runOnBackgroundThread(RegionGuard::check);
        return true;
    }

    private static boolean isSoundCloudHost(String host) {
        host = host.toLowerCase(Locale.US);
        return host.endsWith("soundcloud.com") || host.endsWith("sndcdn.com") || host.endsWith("soundcloud.cloud")
                || host.endsWith("snd.sc");
    }

    /**
     * Forgets the known country and checks it again in the background, for example after the user
     * switched a VPN on the same network. The callback, if any, runs on the main thread.
     */
    public static void recheck(Runnable onDone) {
        Utils.runOnBackgroundThread(() -> {
            synchronized (RegionGuard.class) {
                country = null;
                lastFailedCheck = 0;
                toastShown = false;
                check();
            }
            if (onDone != null) Utils.runOnMainThread(onDone);
        });
    }

    private static void recheckIfStale() {
        if (recheckRunning || System.currentTimeMillis() - checkedAt < BLOCKED_RECHECK_MS) return;
        recheckRunning = true;
        Utils.runOnBackgroundThread(() -> {
            try {
                String previous = country;
                synchronized (RegionGuard.class) {
                    country = null;
                    check();
                    // Keep the old answer if the new check failed, so a dead network does not unblock anything.
                    if (country == null && previous != null) country = previous;
                }
                if (country != null && !country.equals(previous)) toastShown = false;
            } finally {
                recheckRunning = false;
            }
        });
    }

    /** Checks the country once for all waiting requests. Null means the check failed. */
    private static synchronized String check() {
        if (country != null) return country;
        checkedAt = System.currentTimeMillis();
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
        lastFailedCheck = System.currentTimeMillis();
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
                    lastFailedCheck = 0;
                    toastShown = false;
                }

                @Override
                public void onLinkPropertiesChanged(Network network, android.net.LinkProperties properties) {
                    // A new local address or DNS on the same network often means a new public IP too.
                    country = null;
                    lastFailedCheck = 0;
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
