package app.revanced.extension.soundcloud.network;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import app.revanced.extension.shared.Logger;

/** Asks Cloudflare which country the public IP address of the current route belongs to. */
final class CountryTrace {
    private static final int TIMEOUT_MS = 6_000;
    private static final long FOREGROUND_WAIT_MS = 3_000;

    private CountryTrace() {
    }

    /** Asks Cloudflare for the country of the current public IP. Null means the check failed. */
    static String request() {
        try {
            HttpURLConnection connection = (HttpURLConnection)
                    new URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            // A new connection for every check: one kept alive from before a VPN was switched would answer with the
            // country of the old route.
            connection.setRequestProperty("Connection", "close");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String found = countryOf(line);
                    if (found != null) {
                        Logger.printInfo(() -> "Region guard: country " + found);
                        return found;
                    }
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Region guard: country check failed: " + ex);
        }
        return null;
    }

    /** The country code of a line of the trace ("loc=DE"), or null for any other line. */
    static String countryOf(String line) {
        if (!line.startsWith("loc=")) return null;
        return line.substring(4).trim().toUpperCase(Locale.US);
    }

    /**
     * Right after launch Android blocks the network of an app that is not on screen yet, and the failed
     * DNS answer stays cached for several seconds, so a check started then failed and SoundCloud showed
     * "No internet connection". Waits up to 3 seconds for the app to come to the foreground.
     */
    static void waitUntilForeground() {
        long deadline = System.currentTimeMillis() + FOREGROUND_WAIT_MS;
        android.app.ActivityManager.RunningAppProcessInfo info = new android.app.ActivityManager.RunningAppProcessInfo();
        while (System.currentTimeMillis() < deadline) {
            android.app.ActivityManager.getMyMemoryState(info);
            if (info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) return;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
