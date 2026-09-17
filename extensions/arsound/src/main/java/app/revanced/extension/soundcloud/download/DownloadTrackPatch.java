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
 * Adds a "Download" row to the track menu.
 * <p>
 * It first uses the same endpoint as the "Download file" button on soundcloud.com. If an author
 * has not enabled that endpoint, it resolves the progressive stream that the official player uses.
 * Subscription-only and preview-only tracks are explicitly excluded.
 */
@SuppressWarnings("unused")
public final class DownloadTrackPatch {
    private static final String ROW_TAG = "arsound_download_row";
    private static final String PREFERENCES_NAME = "revanced_soundcloud_downloads";
    private static final String DOWNLOADED_TRACKS = "downloaded_tracks";
    /** Maps a track id to the file name in Music/Arsound. Downloads made before this key existed have no entry. */
    private static final String TRACK_FILE_PREFIX = "track_file_";
    private static final Pattern TRACK_ID = Pattern.compile("(\\d+)$");
    private static final String API_ROOT = "https://api-v2.soundcloud.com";

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

            Utils.runOnMainThread(() -> {
                addDownloadRow(dialog, trackId);
                app.revanced.extension.soundcloud.local.LocalAdditions.addTrackMenuRow(dialog, trackUrn);
            });
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
            return iconState(DownloadProgress.isDownloading(trackId) ? "DOWNLOADING" : "DOWNLOADED");
        } catch (Exception ex) {
            Logger.printException(() -> "getDownloadIcon failure", ex);
            return icon;
        }
    }

    private static final java.util.Map<String, Object> iconStates = new java.util.concurrent.ConcurrentHashMap<>();

    /** SoundCloud's download icon in the given step: DOWNLOADED, or DOWNLOADING with its spinner. */
    private static Object iconState(String step) throws Exception {
        Object state = iconStates.get(step);
        if (state != null) return state;
        Class<?> viewStateClass = Class.forName("com.soundcloud.android.ui.components.labels.icons.DownloadIcon$ViewState");
        Class<?> stepClass = Class.forName("com.soundcloud.android.ui.components.labels.icons.DownloadIcon$Step");
        state = viewStateClass.getConstructor(stepClass).newInstance(stepClass.getMethod("valueOf", String.class).invoke(null, step));
        iconStates.put(step, state);
        return state;
    }

    /**
     * Injection point. Called when a playlist cell builds its download icon.
     *
     * @return The spinning icon while tracks started from this playlist are downloading, otherwise the original.
     */
    public static Object getPlaylistDownloadIcon(Object icon, Object playlist) {
        try {
            Object urn = playlist.getClass().getMethod("getUrn").invoke(playlist);
            String id = parseTrackId(urn);
            return DownloadProgress.isPlaylistDownloading(id) ? iconState("DOWNLOADING") : icon;
        } catch (Exception ex) {
            Logger.printException(() -> "getPlaylistDownloadIcon failure", ex);
            return icon;
        }
    }

    public static String parseTrackId(Object trackUrn) {
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
        icon.setImageResource(arsoundIcon(downloaded ? "ic_actions_downloaded" : "ic_actions_download_initial"));

        row.setBackgroundResource(selectableBackground(context));
        row.setOnClickListener(v -> {
            dialog.dismiss();
            requestDownload(context.getApplicationContext(), trackId);
        });

        parent.addView(row, parent.indexOfChild(menuItems) + 1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static void requestDownload(Context context, String trackId) {
        Toast.makeText(context, text("Подготавливаю скачивание…", "Preparing download…"), Toast.LENGTH_SHORT).show();

        Utils.runOnBackgroundThread(() -> {
            try {
                if (getDownloadState(context, trackId) == DownloadState.IN_PROGRESS) {
                    showToast(context, text("Этот трек уже скачивается", "This track is already downloading"));
                    return;
                }
                String fileUrl = resolveDownloadUrl(trackId);
                if (fileUrl == null) {
                    showToast(context, text("Этот трек недоступен для скачивания", "This track is not available for download"));
                    return;
                }
                // "Download again" replaces the file instead of saving a second copy next to it.
                java.io.File previous = getDownloadedFile(trackId);
                if (previous != null && !previous.delete()) {
                    Logger.printInfo(() -> "Could not delete the previous file " + previous);
                }
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
    public static String[] apiGet(String url) throws Exception {
        HttpURLConnection connection = openApiConnection(url);

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
     * Starts a download through the author-provided file URL or the public progressive stream.
     * Subscription-only and preview-only tracks never pass this method.
     *
     * @return True if the download started.
     */
    static boolean downloadSilently(Context context, String trackId) throws Exception {
        return downloadSilently(context, trackId, null);
    }

    /**
     * @param resolvedUrl A file URL resolved moments ago, or null to resolve it now.
     */
    static boolean downloadSilently(Context context, String trackId, String resolvedUrl) throws Exception {
        return downloadSilently(context, trackId, resolvedUrl, null);
    }

    /**
     * @param playlistId The playlist the download was started from, which then shows the download icon.
     */
    static boolean downloadSilently(Context context, String trackId, String resolvedUrl, String playlistId) throws Exception {
        String fileUrl = resolvedUrl != null ? resolvedUrl : resolveDownloadUrl(trackId);
        if (fileUrl == null) return false;
        enqueue(context, trackId, fileUrl, false, playlistId);
        return true;
    }

    /** Resolves a fresh URL for each task because CDN stream URLs expire. */
    static String resolveDownloadUrl(String trackId) throws Exception {
        String[] directDownload = apiGet(API_ROOT + "/tracks/" + trackId + "/download");
        if (directDownload[1] != null) {
            String redirect = new JSONObject(directDownload[1]).optString("redirectUri");
            if (!redirect.isEmpty()) return redirect;
        }

        String[] trackResponse = apiGet(API_ROOT + "/tracks/" + trackId);
        if (trackResponse[1] == null) {
            Logger.printInfo(() -> "Could not load stream metadata for " + trackId + ", HTTP " + trackResponse[0]);
            return null;
        }

        JSONObject track = new JSONObject(trackResponse[1]);
        if (isRestrictedTrack(track)) {
            Logger.printInfo(() -> "Not downloading restricted track " + trackId);
            return null;
        }

        JSONObject progressive = findProgressiveTranscoding(track.optJSONObject("media"));
        if (progressive == null) {
            Logger.printInfo(() -> "No progressive stream available for " + trackId);
            return null;
        }

        String endpoint = progressive.optString("url");
        if (endpoint.isEmpty()) return null;
        String[] streamResponse = apiGet(endpoint);
        if (streamResponse[1] == null) return null;
        String streamUrl = new JSONObject(streamResponse[1]).optString("url");
        return streamUrl.isEmpty() ? null : streamUrl;
    }

    /** Sends a JSON body with the given method. Returns the response code. */
    public static int apiSend(String method, String url, String json) throws Exception {
        HttpURLConnection connection = openApiConnection(url);
        connection.setRequestMethod(method);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (java.io.OutputStream output = connection.getOutputStream()) {
            output.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return connection.getResponseCode();
    }

    private static HttpURLConnection openApiConnection(String url) throws Exception {
        app.revanced.extension.soundcloud.network.RegionGuard.throwIfBlocked(new URL(url).getHost());
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        String authorization = getAuthorization();
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        return connection;
    }

    /** Prefer a single progressive MP3/AAC file; HLS is intentionally not saved as a playlist. */
    private static JSONObject findProgressiveTranscoding(JSONObject media) {
        if (media == null) return null;
        org.json.JSONArray transcodings = media.optJSONArray("transcodings");
        if (transcodings == null) return null;

        for (int i = 0; i < transcodings.length(); i++) {
            JSONObject transcoding = transcodings.optJSONObject(i);
            if (transcoding == null) continue;
            JSONObject format = transcoding.optJSONObject("format");
            if (format == null || !"progressive".equals(format.optString("protocol"))) continue;
            String mimeType = format.optString("mime_type");
            if (mimeType.startsWith("audio/")) return transcoding;
        }
        return null;
    }

    /** Keeps the patch within the user's existing free, full-track playback entitlement. */
    private static boolean isRestrictedTrack(JSONObject track) {
        String policy = track.optString("policy").toUpperCase(Locale.US);
        String monetization = track.optString("monetization_model").toUpperCase(Locale.US);
        return policy.contains("SNIP") || policy.contains("BLOCK") || policy.contains("SUB")
                || monetization.contains("SUB") || monetization.contains("GO_PLUS");
    }

    private static void enqueue(Context context, String trackId, String fileUrl) {
        enqueue(context, trackId, fileUrl, true);
    }

    private static void enqueue(Context context, String trackId, String fileUrl, boolean notify) {
        enqueue(context, trackId, fileUrl, notify, null);
    }

    private static void enqueue(Context context, String trackId, String fileUrl, boolean notify, String playlistId) {
        Uri uri = Uri.parse(fileUrl);
        String fileName = uri.getLastPathSegment();
        // Author downloads can come from a link without an extension; players then do not recognise the file.
        if (fileName == null || !fileName.contains(".")) fileName = "soundcloud-" + trackId + ".mp3";

        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle(fileName)
                // Downloads stay silent, like the rest of the app. Requires DOWNLOAD_WITHOUT_NOTIFICATION.
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "Arsound/" + fileName);

        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        manager.enqueue(request);

        Set<String> tracks = new HashSet<>(getDownloadedTracks());
        tracks.add(trackId);
        getPreferences().edit()
                .putStringSet(DOWNLOADED_TRACKS, tracks)
                .putString(TRACK_FILE_PREFIX + trackId, fileName)
                .apply();
        DownloadProgress.onStarted(trackId, playlistId);

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

    /**
     * @return The downloaded file of the track, or null if it was not downloaded, is still downloading
     * or was deleted.
     */
    public enum DownloadState {NOT_DOWNLOADED, IN_PROGRESS, DOWNLOADED}

    /**
     * Whether a track was already downloaded by Arsound, so it is not downloaded twice.
     * Tracks downloaded before the file name was remembered count as downloaded: their file cannot be checked.
     */
    public static DownloadState getDownloadState(Context context, String trackId) {
        if (getDownloadedFile(trackId) != null) return DownloadState.DOWNLOADED;
        if (!getDownloadedTracks().contains(trackId)) return DownloadState.NOT_DOWNLOADED;

        SharedPreferences preferences = getPreferences();
        String fileName = preferences == null ? null : preferences.getString(TRACK_FILE_PREFIX + trackId, null);
        if (fileName == null) return DownloadState.DOWNLOADED;

        // Marked, but the file is not complete: either still downloading, or the file was deleted.
        try {
            DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Query query = new DownloadManager.Query().setFilterByStatus(
                    DownloadManager.STATUS_PENDING | DownloadManager.STATUS_RUNNING | DownloadManager.STATUS_PAUSED);
            try (android.database.Cursor cursor = manager.query(query)) {
                int title = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);
                while (cursor.moveToNext()) {
                    if (fileName.equals(cursor.getString(title))) return DownloadState.IN_PROGRESS;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not check running downloads", ex);
        }
        return DownloadState.NOT_DOWNLOADED;
    }

    public static java.io.File getDownloadedFile(String trackId) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null || trackId == null) return null;
        String fileName = preferences.getString(TRACK_FILE_PREFIX + trackId, null);
        if (fileName == null) return null;

        java.io.File file = new java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Arsound/" + fileName);
        // Early versions saved some files without an extension; such files may have been renamed to ".mp3" since.
        if (!file.isFile() && !fileName.contains(".")) {
            java.io.File withExtension = new java.io.File(file.getPath() + ".mp3");
            if (withExtension.isFile()) file = withExtension;
        }
        // DownloadManager writes into a temporary file first, so a present file with content is complete.
        return file.isFile() && file.length() > 0 && file.canRead() ? file : null;
    }

    /**
     * Finds the files of tracks downloaded before the file name was remembered.
     * <p>
     * Such tracks are marked as downloaded, but their file cannot be told apart from the others: it is named
     * after the stream on SoundCloud's CDN, not after the track. Without the name they were streamed instead of
     * played from the file, and did not play at all without a network. The stream URL still ends with the same
     * name, so it is resolved again once, while the network works.
     *
     * @return How many files were found.
     */
    public static int rememberOldFileNames() {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return 0;
        java.io.File folder = new java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Arsound");

        int found = 0;
        for (String trackId : getDownloadedTracks()) {
            if (preferences.contains(TRACK_FILE_PREFIX + trackId)) continue;
            try {
                String url = resolveDownloadUrl(trackId);
                String name = url == null ? null : Uri.parse(url).getLastPathSegment();
                if (name == null) continue;
                if (!new java.io.File(folder, name).isFile()) {
                    String alternative = name.replaceFirst("(\\.[^.]+)$", "-1$1");
                    if (!new java.io.File(folder, alternative).isFile()) continue;
                    name = alternative;
                }
                preferences.edit().putString(TRACK_FILE_PREFIX + trackId, name).apply();
                found++;
            } catch (Exception ex) {
                // Most likely no network; the next start tries again.
                Logger.printInfo(() -> "Could not find the file of " + trackId + ": " + ex);
                break;
            }
        }
        int total = found;
        Logger.printInfo(() -> "Old downloads: found files of " + total + " tracks");
        return found;
    }

    /** Whether a downloaded file exists but cannot be read, because it was created before a reinstall. */
    public static boolean hasUnreadableDownloads() {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return false;
        java.io.File folder = new java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Arsound");
        for (String trackId : getDownloadedTracks()) {
            String name = preferences.getString(TRACK_FILE_PREFIX + trackId, null);
            if (name == null) continue;
            java.io.File file = new java.io.File(folder, name);
            if (file.exists() && !file.canRead()) return true;
        }
        return false;
    }

    /** File names in Music/Arsound mapped to their track ids. */
    static java.util.Map<String, String> trackIdsByFileName() {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return result;
        for (java.util.Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(TRACK_FILE_PREFIX) && entry.getValue() instanceof String) {
                result.put((String) entry.getValue(), entry.getKey().substring(TRACK_FILE_PREFIX.length()));
            }
        }
        return result;
    }

    public static Set<String> getDownloadedTrackIds() {
        return new HashSet<>(getDownloadedTracks());
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

    /**
     * Rows added by Arsound to SoundCloud menus show the Arsound icon, so mod features are recognizable.
     *
     * @param fallback The SoundCloud icon used if the branding resources are not patched in.
     */
    public static int arsoundIcon(String fallback) {
        int icon = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_icon");
        return icon != 0 ? icon : Utils.getResourceIdentifier(ResourceType.DRAWABLE, fallback);
    }

    /** Creates a menu row styled like SoundCloud's own action list items. */
    public static ViewGroup createMenuRow(Context context, String title, String iconName, View.OnClickListener listener) {
        ViewGroup row = createConstraintLayout(context);
        row.setMinimumHeight(dimen(context, "action_list_default_height"));
        LayoutInflater.from(context).inflate(
                Utils.getResourceIdentifier(ResourceType.LAYOUT, "layout_action_list_item"), row, true);

        TextView titleView = row.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "action_list_item_title"));
        ImageView icon = row.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "action_list_item_icon_start"));
        hide(row, "action_list_item_download_icon");
        hide(row, "action_list_item_icon_end");
        hide(row, "action_list_selectable_check_icon");

        titleView.setText(title);
        icon.setImageResource(arsoundIcon(iconName));
        row.setBackgroundResource(selectableBackground(context));
        row.setOnClickListener(listener);
        return row;
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
