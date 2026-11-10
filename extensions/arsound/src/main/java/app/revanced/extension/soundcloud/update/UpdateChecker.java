package app.revanced.extension.soundcloud.update;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
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
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.debug.CrashLog;
import app.revanced.extension.soundcloud.download.DownloadIndex;
import app.revanced.extension.soundcloud.download.DownloadProgress;
import app.revanced.extension.soundcloud.download.MusicAccess;
import app.revanced.extension.soundcloud.download.ProgressPill;
import app.revanced.extension.soundcloud.local.LocalSheet;
import app.revanced.extension.soundcloud.local.WatchFolder;
import app.revanced.extension.soundcloud.login.LoginLogsButton;
import app.revanced.extension.soundcloud.network.NetworkBanner;
import app.revanced.extension.soundcloud.permissions.WelcomePermissions;
import app.revanced.extension.soundcloud.player.VivoMusicCard;
import app.revanced.extension.soundcloud.search.SearchSourceSwitch;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.shared.Screens;

/**
 * Checks the GitHub releases of Arsound for a newer version. Nothing is downloaded or installed by the app:
 * it offers the ready APK in the Telegram channel or the patches on GitHub.
 */
@SuppressWarnings("unused")
public final class UpdateChecker {
    /** Must match {@code version} in gradle.properties. */
    public static final String VERSION = "1.2.1-test.2";

    public static final String REPOSITORY_URL = "https://github.com/Allvoid/arsound";
    /** The ready APK of every version is posted here. */
    public static final String TELEGRAM_URL = "https://t.me/arsound_git";
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
    /** The release to offer, until the user has closed its sheet in any way. */
    private static Release pendingRelease;
    /** The screen the sheet of {@link #pendingRelease} was put on, and the sheet. */
    private static WeakReference<Activity> sheetActivity = new WeakReference<>(null);
    private static WeakReference<Dialog> sheet = new WeakReference<>(null);
    private static boolean checkedThisLaunch;

    private UpdateChecker() {
    }

    /** The features that follow the screens, in the order they run on each screen. */
    private static ScreenHooks screenHooks() {
        return new ScreenHooks()
                // The network of an app that is not on screen yet is blocked, so the check starts here.
                .add("update check", activity -> startCheckOnce())
                .add("update sheet", activity -> showPendingRelease())
                .add("network banner", NetworkBanner::onActivityResumed, NetworkBanner::onActivityPaused)
                .add("permissions", WelcomePermissions::onActivityResumed)
                .add("music access", MusicAccess::onActivityResumed)
                .add("download index", DownloadIndex::onActivityResumed)
                .add("vivo card", VivoMusicCard::onActivityResumed)
                .add("download progress", DownloadProgress::onActivityResumed, DownloadProgress::onActivityPaused)
                .add("progress pill", ProgressPill::onActivityResumed)
                .add("search switch", SearchSourceSwitch::onActivityResumed, SearchSourceSwitch::onActivityPaused)
                .add("watch folder", WatchFolder::onActivityResumed)
                .add("login logs", LoginLogsButton::onActivityResumed)
                .add("motion blur", app.revanced.extension.soundcloud.motion.MotionBlur::onActivityResumed,
                        app.revanced.extension.soundcloud.motion.MotionBlur::onActivityPaused)
                // Changes kept back to be written together reach the disk before the app may be ended in the background.
                .add("batched preferences", activity -> {
                }, activity -> app.revanced.extension.soundcloud.shared.BatchedPreferences.flushAll());
    }

    /**
     * Injection point. Called when the application starts.
     * Checks for an update in the background and shows it on the first screen that opens.
     */
    public static void onApplicationCreate(Application application) {
        // First, so a crash anywhere after this is written down.
        CrashLog.install(application);
        app.revanced.extension.soundcloud.player.SleepTimer.onAppStart(application);
        app.revanced.extension.soundcloud.offline.NativeOffline.onAppStart(application);
        ScreenHooks hooks = screenHooks();
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                resumedActivity = new WeakReference<>(activity);
                hooks.resumed(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (resumedActivity.get() == activity) resumedActivity = new WeakReference<>(null);
                hooks.paused(activity);
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

    /** See {@link ReleaseVersion#compare}. */
    static int compareVersions(String first, String second) {
        return ReleaseVersion.compare(first, second);
    }

    /**
     * Puts the sheet of the found release on the main screen. Not on any other: the launcher's screen that opens the
     * main one closes at once and would take the sheet with it, so it flashed for a moment and was gone. The release
     * stays pending until the user closes its sheet; a sheet lost with its screen (closed or made again) comes back on
     * the next main screen.
     */
    private static void showPendingRelease() {
        Activity activity = resumedActivity.get();
        Release release = pendingRelease;
        if (activity == null || release == null || activity.isFinishing() || !Screens.isMainActivity(activity)) return;
        Dialog shown = sheet.get();
        if (shown != null && shown.isShowing() && sheetActivity.get() == activity) return;
        sheetActivity = new WeakReference<>(activity);
        sheet = new WeakReference<>(showUpdateSheet(activity, release, () -> {
            if (pendingRelease == release) pendingRelease = null;
        }));
    }

    public static void showUpdateSheet(Context context, Release release) {
        showUpdateSheet(context, release, () -> {
        });
    }

    /**
     * @param onClosed Runs when the user closes the sheet: with one of its rows, a swipe, a tap outside or back.
     * @return The sheet, or null if it could not be shown.
     */
    private static Dialog showUpdateSheet(Context context, Release release, Runnable onClosed) {
        boolean russian = Lang.russian();
        List<LocalSheet.Item> items = LocalSheet.items();
        // Nothing is downloaded by the app: the user picks where to get the new version.
        items.add(new LocalSheet.Item(russian ? "Скачать APK в Telegram" : "Download the APK on Telegram",
                "ic_actions_download", () -> {
            onClosed.run();
            openUrl(context, TELEGRAM_URL);
        }));
        items.add(new LocalSheet.Item(russian ? "Патчи на GitHub (для ReVanced Manager)" : "Patches on GitHub (for ReVanced Manager)",
                "ic_actions_link", () -> {
            onClosed.run();
            openUrl(context, release.url);
        }));
        items.add(new LocalSheet.Item(russian ? "Позже" : "Later", "ic_actions_close", onClosed));
        return LocalSheet.show(context,
                russian ? "Доступно обновление" : "Update available",
                (russian ? "Arsound " + release.version + ", у вас " : "Arsound " + release.version + ", you have ")
                        + VERSION,
                items, onClosed);
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
