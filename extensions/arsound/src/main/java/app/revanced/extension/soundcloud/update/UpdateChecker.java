package app.revanced.extension.soundcloud.update;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.local.LocalSheet;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Checks the GitHub releases of Arsound for a newer version.
 */
@SuppressWarnings("unused")
public final class UpdateChecker {
    /** Must match {@code version} in gradle.properties. */
    public static final String VERSION = "0.3.0";

    public static final String REPOSITORY_URL = "https://github.com/Allvoid/arsound";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/Allvoid/arsound/releases/latest";

    public interface Callback {
        /** Called on the main thread. {@code release} is null if there is no newer version or the check failed. */
        void onResult(Release release, boolean failed);
    }

    public static final class Release {
        public final String version;
        public final String url;

        Release(String version, String url) {
            this.version = version;
            this.url = url;
        }
    }

    private static WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private static Release pendingRelease;
    private static boolean checkedThisLaunch;

    private UpdateChecker() {
    }

    /**
     * Injection point. Called when the application starts.
     * Checks for an update in the background and shows it on the first screen that opens.
     */
    public static void onApplicationCreate(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                resumedActivity = new WeakReference<>(activity);
                // The network of an app that is not on screen yet is blocked, so the check starts here.
                startCheckOnce();
                showPendingRelease();
                app.revanced.extension.soundcloud.network.NetworkBanner.onActivityResumed(activity);
                app.revanced.extension.soundcloud.permissions.WelcomePermissions.onActivityResumed(activity);
                app.revanced.extension.soundcloud.download.MusicAccess.onActivityResumed(activity);
                app.revanced.extension.soundcloud.download.DownloadProgress.onActivityResumed(activity);
                app.revanced.extension.soundcloud.search.SearchSourceSwitch.onActivityResumed(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (resumedActivity.get() == activity) resumedActivity = new WeakReference<>(null);
                app.revanced.extension.soundcloud.network.NetworkBanner.onActivityPaused(activity);
                app.revanced.extension.soundcloud.download.DownloadProgress.onActivityPaused(activity);
                app.revanced.extension.soundcloud.search.SearchSourceSwitch.onActivityPaused(activity);
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });

    }

    /** The activity on screen, or null. */
    public static Activity resumedActivity() {
        return resumedActivity.get();
    }

    private static void startCheckOnce() {
        if (checkedThisLaunch || !Settings.isUpdateCheckEnabled()) return;
        checkedThisLaunch = true;
        check((release, failed) -> {
            if (release == null) return;
            pendingRelease = release;
            showPendingRelease();
        });
    }

    /** Checks for a newer release in the background. */
    public static void check(Callback callback) {
        Utils.runOnBackgroundThread(() -> {
            Release release = null;
            boolean failed = false;
            try {
                release = fetchNewerRelease();
            } catch (Exception ex) {
                failed = true;
                Logger.printInfo(() -> "Update check failed", ex);
            }
            Release result = release;
            boolean resultFailed = failed;
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result, resultFailed));
        });
    }

    private static Release fetchNewerRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            int code = connection.getResponseCode();
            // No published release yet.
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null;
            if (code != HttpURLConnection.HTTP_OK) throw new IllegalStateException("HTTP " + code);

            JSONObject json = new JSONObject(read(connection.getInputStream()));
            String tag = json.optString("tag_name", "");
            String url = json.optString("html_url", REPOSITORY_URL + "/releases/latest");
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            return compareVersions(version, VERSION) > 0 ? new Release(version, url) : null;
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream stream) throws Exception {
        try (InputStream input = stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }

    /** Compares dotted numeric versions, ignoring any suffix such as "-beta". */
    static int compareVersions(String first, String second) {
        String[] a = first.split("[^0-9]+");
        String[] b = second.split("[^0-9]+");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length && !a[i].isEmpty() ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && !b[i].isEmpty() ? Integer.parseInt(b[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static void showPendingRelease() {
        Activity activity = resumedActivity.get();
        Release release = pendingRelease;
        if (activity == null || release == null || activity.isFinishing()) return;
        pendingRelease = null;
        showUpdateSheet(activity, release);
    }

    public static void showUpdateSheet(Context context, Release release) {
        boolean russian = Locale.getDefault().getLanguage().equals("ru");
        java.util.List<LocalSheet.Item> items =LocalSheet.items();
        items.add(new LocalSheet.Item(russian ? "Скачать" : "Download", "ic_actions_download",
                () -> openUrl(context, release.url)));
        items.add(new LocalSheet.Item(russian ? "Позже" : "Later", "ic_actions_close", null));
        LocalSheet.show(context,
                russian ? "Доступно обновление" : "Update available",
                (russian ? "Arsound " + release.version + ", у вас " : "Arsound " + release.version + ", you have ")
                        + VERSION,
                items);
    }

    public static void openUrl(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open " + url, ex);
        }
    }
}
