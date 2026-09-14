package app.revanced.extension.soundcloud.download;

import android.app.DownloadManager;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;

/**
 * Adds a "Download" row to the track menu for tracks whose artist allowed free downloads.
 * <p>
 * Uses the same public endpoint as the "Download file" button on soundcloud.com,
 * which only returns a file when the artist enabled downloads for the track.
 */
@SuppressWarnings("unused")
public final class DownloadTrackPatch {
    private static final String ROW_TAG = "arsound_download_row";
    private static final String PREFERENCES_NAME = "revanced_soundcloud_downloads";
    private static final String DOWNLOADED_TRACKS = "downloaded_tracks";
    private static final Pattern TRACK_ID = Pattern.compile("(\\d+)$");

    private static final boolean RUSSIAN = "ru".equals(Locale.getDefault().getLanguage());

    /**
     * The SoundCloud OAuth helper, used to authorize the download request like the app does.
     */
    private static volatile Object oAuth;

    static String text(String russian, String english) {
        return RUSSIAN ? russian : english;
    }

    /**
     * Injection point. Called when SoundCloud creates its OAuth helper.
     */
    public static void setOAuth(Object instance) {
        oAuth = instance;
    }

    /**
     * Injection point. Called when the track menu receives its data.
     */
    public static void onTrackMenu(Dialog dialog, Object trackUrn) {
        try {
            String trackId = parseTrackId(trackUrn);
            if (trackId == null) return;

            Utils.runOnMainThread(() -> addDownloadRow(dialog, trackId));
        } catch (Exception ex) {
            Logger.printException(() -> "onTrackMenu failure", ex);
        }
    }

    private static final ThreadLocal<Object> currentTrackItem = new ThreadLocal<>();

    /**
     * Injection point. Called when a track cell starts building its metadata line.
     */
    public static void setCurrentTrackItem(Object trackItem) {
        currentTrackItem.set(trackItem);
    }

    /**
     * Injection point. Called when a track cell builds its "downloaded" icon.
     *
     * @param icon The icon SoundCloud chose for its own offline state, or null.
     * @return SoundCloud's "downloaded" icon if the track was downloaded with this patch, otherwise the original icon.
     */
    public static Object getDownloadIcon(Object icon) {
        Object trackItem = currentTrackItem.get();
        if (trackItem == null) return icon;

        try {
            Object trackUrn = trackItem.getClass().getMethod("getUrn").invoke(trackItem);
            String trackId = parseTrackId(trackUrn);
            if (trackId == null || !getDownloadedTracks().contains(trackId)) return icon;

            if (downloadedIcon == null) {
                Class<?> viewStateClass = Class.forName("com.soundcloud.android.ui.components.labels.icons.DownloadIcon$ViewState");
                Class<?> stepClass = Class.forName("com.soundcloud.android.ui.components.labels.icons.DownloadIcon$Step");
                Object downloadedStep = stepClass.getMethod("valueOf", String.class).invoke(null, "DOWNLOADED");
                downloadedIcon = viewStateClass.getConstructor(stepClass).newInstance(downloadedStep);
            }
            return downloadedIcon;
        } catch (Exception ex) {
            Logger.printException(() -> "getDownloadIcon failure", ex);
            return icon;
        }
    }

    private static volatile Object downloadedIcon;

    static String parseTrackId(Object trackUrn) {
        if (trackUrn == null) return null;
        Matcher matcher = TRACK_ID.matcher(trackUrn.toString());
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void addDownloadRow(Dialog dialog, String trackId) {
        View menuItems = dialog.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "menuItems"));
        if (menuItems == null || !(menuItems.getParent() instanceof LinearLayout)) return;

        LinearLayout parent = (LinearLayout) menuItems.getParent();
        View existing = parent.findViewWithTag(ROW_TAG);
        if (existing != null) parent.removeView(existing);

        Context context = dialog.getContext();
        ViewGroup row = createConstraintLayout(context);
        row.setTag(ROW_TAG);
        row.setMinimumHeight(dimen(context, "action_list_default_height"));
        LayoutInflater.from(context).inflate(
                Utils.getResourceIdentifier(ResourceType.LAYOUT, "layout_action_list_item"), row, true);

        TextView title = row.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "action_list_item_title"));
        ImageView icon = row.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "action_list_item_icon_start"));
        hide(row, "action_list_item_download_icon");
        hide(row, "action_list_item_icon_end");
        hide(row, "action_list_selectable_check_icon");

        boolean downloaded = getDownloadedTracks().contains(trackId);
        title.setText(downloaded
                ? text("Скачать файл ещё раз", "Download file again")
                : text("Скачать файл", "Download file"));
        icon.setImageResource(Utils.getResourceIdentifier(ResourceType.DRAWABLE,
                downloaded ? "ic_actions_downloaded" : "ic_actions_download_initial"));

        row.setBackgroundResource(selectableBackground(context));
        row.setOnClickListener(v -> {
            dialog.dismiss();
            requestDownload(context.getApplicationContext(), trackId);
        });

        parent.addView(row, parent.indexOfChild(menuItems) + 1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static void requestDownload(Context context, String trackId) {
        Toast.makeText(context, text("Проверяю, можно ли скачать…", "Checking download…"), Toast.LENGTH_SHORT).show();

        Utils.runOnBackgroundThread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection)
                        new URL("https://api-v2.soundcloud.com/tracks/" + trackId + "/download").openConnection();
                String authorization = getAuthorization();
                if (authorization != null) connection.setRequestProperty("Authorization", authorization);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);

                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    Logger.printInfo(() -> "Download not available for track " + trackId + ", HTTP " + code);
                    showToast(context, code == 404 || code == 403 || code == 401
                            ? text("Автор не разрешил скачивание этого трека", "The artist has not enabled downloads for this track")
                            : text("Не удалось скачать, ошибка " + code, "Download failed, error " + code));
                    return;
                }

                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }

                String fileUrl = new JSONObject(body.toString()).getString("redirectUri");
                enqueue(context, trackId, fileUrl);
            } catch (Exception ex) {
                Logger.printException(() -> "Download request failure", ex);
                showToast(context, text("Не удалось скачать трек", "Could not download the track"));
            }
        });
    }

    /**
     * @return The response code and body of an authorized GET request to the SoundCloud API.
     */
    static String[] apiGet(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        String authorization = getAuthorization();
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);

        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) return new String[]{String.valueOf(code), null};

        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        }
        return new String[]{String.valueOf(code), body.toString()};
    }

    /**
     * Starts the download of a track the artist allowed to download, without any messages.
     *
     * @return True if the download started.
     */
    static boolean downloadSilently(Context context, String trackId) throws Exception {
        String[] response = apiGet("https://api-v2.soundcloud.com/tracks/" + trackId + "/download");
        if (response[1] == null) return false;

        enqueue(context, trackId, new JSONObject(response[1]).getString("redirectUri"), false);
        return true;
    }

    private static void enqueue(Context context, String trackId, String fileUrl) {
        enqueue(context, trackId, fileUrl, true);
    }

    private static void enqueue(Context context, String trackId, String fileUrl, boolean notify) {
        Uri uri = Uri.parse(fileUrl);
        String fileName = uri.getLastPathSegment();
        if (fileName == null || !fileName.contains(".")) fileName = "soundcloud-" + trackId;

        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle(fileName)
                // Downloads stay silent, like the rest of the app. Requires DOWNLOAD_WITHOUT_NOTIFICATION.
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "Arsound/" + fileName);

        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        manager.enqueue(request);

        Set<String> tracks = new HashSet<>(getDownloadedTracks());
        tracks.add(trackId);
        getPreferences().edit().putStringSet(DOWNLOADED_TRACKS, tracks).apply();

        if (notify) showToast(context, text("Скачивание началось: Музыка/Arsound", "Downloading to Music/Arsound"));
    }

    /**
     * SoundCloud's OAuth helper has a single public method without parameters returning the header value.
     * Its name is obfuscated, so it is looked up by signature.
     */
    private static String getAuthorization() {
        Object instance = oAuth;
        if (instance == null) return null;

        try {
            for (Method method : instance.getClass().getDeclaredMethods()) {
                if (method.getParameterTypes().length == 0
                        && method.getReturnType() == String.class
                        && !Modifier.isStatic(method.getModifiers())) {
                    String value = (String) method.invoke(instance);
                    if (value != null && value.startsWith("OAuth ") && !value.endsWith("invalidated")) return value;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the OAuth token", ex);
        }
        return null;
    }

    private static Set<String> getDownloadedTracks() {
        SharedPreferences preferences = getPreferences();
        return preferences == null ? new HashSet<>() : preferences.getStringSet(DOWNLOADED_TRACKS, new HashSet<>());
    }

    private static SharedPreferences getPreferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    static void showToast(Context context, String message) {
        Utils.runOnMainThread(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    static void hide(View row, String idName) {
        View view = row.findViewById(Utils.getResourceIdentifier(ResourceType.ID, idName));
        if (view != null) view.setVisibility(View.GONE);
    }

    static ViewGroup createConstraintLayout(Context context) {
        try {
            return (ViewGroup) Class.forName("androidx.constraintlayout.widget.ConstraintLayout")
                    .getConstructor(Context.class)
                    .newInstance(context);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("ConstraintLayout not found in SoundCloud", ex);
        }
    }

    static int dimen(Context context, String name) {
        int id = Utils.getResourceIdentifier(ResourceType.DIMEN, name);
        return id == 0 ? 0 : context.getResources().getDimensionPixelSize(id);
    }

    static int selectableBackground(Context context) {
        android.util.TypedValue value = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }
}
