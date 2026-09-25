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
    private static final String DELETE_ROW_TAG = "arsound_delete_row";
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
            // An imported file: nothing to download, but its cover can be picked.
            java.io.File imported = app.revanced.extension.soundcloud.local.LocalAdditions.importedFileOf(trackUrn);
            if (imported != null) {
                Utils.runOnMainThread(() -> app.revanced.extension.soundcloud.local.LocalAdditions.addTrackCoverRow(dialog, imported));
                return;
            }
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
     * @return The spinning icon while tracks started from this playlist are downloading, otherwise the
     * original. The number of downloaded tracks is shown next to the number of tracks instead of an icon.
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
        View existingDelete = parent.findViewWithTag(DELETE_ROW_TAG);
        if (existingDelete != null) parent.removeView(existingDelete);

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

        if (!downloaded) return;
        // Only tracks downloaded by Arsound get this row; music imported from the phone is left alone.
        ViewGroup deleteRow = createMenuRow(context, text("Удалить скачанный файл", "Delete the downloaded file"),
                "ic_actions_delete", v -> {
                    dialog.dismiss();
                    Context appContext = context.getApplicationContext();
                    Utils.runOnBackgroundThread(() -> {
                        boolean deleted = deleteDownload(appContext, trackId);
                        showToast(appContext, deleted
                                ? text("Файл удалён", "The file is deleted")
                                : text("Не удалось удалить файл", "Could not delete the file"));
                        DownloadProgress.onDownloadsDeleted();
                    });
                });
        deleteRow.setTag(DELETE_ROW_TAG);
        parent.addView(deleteRow, parent.indexOfChild(row) + 1, new LinearLayout.LayoutParams(
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
                TrackSource source = resolveSource(trackId);
                if (!source.isDownloadable()) {
                    showToast(context, unavailableMessage(source.status));
                    return;
                }
                // "Download again" replaces the file instead of saving a second copy next to it.
                java.io.File previous = getDownloadedFile(trackId);
                if (previous != null && !previous.delete()) {
                    Logger.printInfo(() -> "Could not delete the previous file " + previous);
                }
                start(context, trackId, source, true, null);
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
        if (code != HttpURLConnection.HTTP_OK) {
            Logger.printInfo(() -> "API request failed, HTTP " + code + " for " + url);
            return new String[]{String.valueOf(code), null};
        }

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
        TrackSource source = resolvedUrl != null ? TrackSource.progressive(resolvedUrl) : resolveSource(trackId);
        return downloadSilently(context, trackId, source, playlistId);
    }

    /**
     * @param source A source resolved moments ago, or null to resolve it now.
     */
    static boolean downloadSilently(Context context, String trackId, TrackSource source, String playlistId) throws Exception {
        if (source == null || !source.isFresh()) source = resolveSource(trackId);
        if (!source.isDownloadable()) return false;
        start(context, trackId, source, false, playlistId);
        return true;
    }

    /** Starts the download through the download manager, or through the HLS assembler. */
    private static void start(Context context, String trackId, TrackSource source, boolean notify, String playlistId) {
        if (source.status == TrackSource.Status.READY) {
            enqueue(context, trackId, source.url, notify, playlistId);
            return;
        }

        // The segments are downloaded, decrypted and packed by the app itself, which takes CPU time.
        DownloadProgress.onAssemblyStarted(trackId, playlistId);
        if (notify) {
            showToast(context, text("Собираю трек из потока, это займёт немного времени…",
                    "Assembling the track from its stream, this takes a moment…"));
        }
        Utils.runOnBackgroundThread(() -> {
            String fileName = null;
            try {
                fileName = HlsDownloader.download(context, trackId, source.url, source.mimeType);
            } finally {
                if (fileName != null) {
                    rememberDownload(trackId, fileName);
                    if (notify) showToast(context, text("Трек сохранён: Музыка/Arsound", "Saved to Music/Arsound"));
                } else if (notify) {
                    showToast(context, text("Не удалось собрать трек", "Could not assemble the track"));
                }
                DownloadProgress.onAssemblyFinished(trackId);
            }
        });
    }

    /** Why a track cannot be downloaded, for the toast of the track menu. */
    private static String unavailableMessage(TrackSource.Status status) {
        switch (status) {
            case AUDIO_ONLY_PREVIEW:
                return text("Это только отрывок трека", "This track is only a preview");
            case SUBSCRIPTION_REQUIRED:
                return text("Этот трек доступен по подписке", "This track needs a subscription");
            case DRM_PROTECTED:
                return text("Этот трек защищён DRM: его можно слушать, но не сохранить",
                        "This track is DRM protected: it plays, but cannot be saved");
            default:
                return text("Этот трек недоступен для скачивания", "This track is not available for download");
        }
    }

    /**
     * Resolves a fresh progressive URL for each task, because CDN stream URLs expire.
     *
     * @return The URL of a single file, or null when the track is only offered as HLS or not at all.
     */
    static String resolveDownloadUrl(String trackId) throws Exception {
        TrackSource source = resolveSource(trackId);
        return source.status == TrackSource.Status.READY ? source.url : null;
    }

    /**
     * Finds out how the audio of a track can be saved.
     * <p>
     * The author-provided file comes first, then the progressive stream of the player, then its HLS
     * playlist, and finally the same request as other client profiles.
     */
    static TrackSource resolveSource(String trackId) throws Exception {
        String[] directDownload = apiGet(API_ROOT + "/tracks/" + trackId + "/download");
        if (directDownload[1] != null) {
            String redirect = new JSONObject(directDownload[1]).optString("redirectUri");
            if (!redirect.isEmpty()) return TrackSource.progressive(redirect);
        }

        TrackSource source = TrackSource.unavailable(TrackSource.Status.UNAVAILABLE);
        String[] trackResponse = apiGet(API_ROOT + "/tracks/" + trackId);
        if (trackResponse[1] == null) {
            Logger.printInfo(() -> "Could not load stream metadata for " + trackId + ", HTTP " + trackResponse[0]);
        } else {
            source = sourceOfTrack(trackId, new JSONObject(trackResponse[1]), DownloadTrackPatch::resolveStreamUrl);
            if (source.isDownloadable()) return source;
        }

        // The app is offered less than the web player: a track that is a preview here can be whole there.
        TrackSource alternative = ClientProfiles.search(trackId);
        if (alternative != null && alternative.isDownloadable()) return alternative;
        // Neither client can save it: the stricter reason of the two is the one worth showing.
        if (alternative != null && source.status == TrackSource.Status.REQUIRES_PROFILE_SEARCH) return alternative;
        return source;
    }

    /** Turns a transcoding endpoint into the URL behind it, as some client. */
    interface StreamResolver {
        String resolve(String endpoint) throws Exception;
    }

    /** Reads the streams of a track response, whichever client asked for it. */
    static TrackSource sourceOfTrack(String trackId, JSONObject track, StreamResolver resolver) throws Exception {
        TrackSource.Status restriction = restrictionOf(track);
        if (restriction != null) {
            Logger.printInfo(() -> "Restricted track " + trackId + ": " + restriction);
            return TrackSource.unavailable(restriction);
        }

        // Every transcoding is tried in turn: the ones a client may not use answer with HTTP 404.
        org.json.JSONArray transcodings = track.optJSONObject("media") == null
                ? null : track.optJSONObject("media").optJSONArray("transcodings");
        if (transcodings == null) return TrackSource.unavailable(TrackSource.Status.REQUIRES_PROFILE_SEARCH);

        boolean drmSeen = false;
        for (JSONObject transcoding : orderedTranscodings(transcodings)) {
            JSONObject format = transcoding.optJSONObject("format");
            String protocol = format == null ? "" : format.optString("protocol");
            String mimeType = format == null ? null : format.optString("mime_type");

            String url = resolver.resolve(transcoding.optString("url"));
            if (url == null) continue;
            if ("progressive".equals(protocol)) return TrackSource.progressive(url);

            // A playlist locked by FairPlay or Widevine hands out its key only to a licence server.
            if (HlsDownloader.isDrmProtected(url)) {
                drmSeen = true;
                Logger.printInfo(() -> "DRM protected stream for " + trackId + ": " + protocol);
                continue;
            }
            return TrackSource.hls(url, mimeType);
        }

        if (drmSeen) return TrackSource.unavailable(TrackSource.Status.DRM_PROTECTED);
        Logger.printInfo(() -> "No stream of this client for " + trackId);
        return TrackSource.unavailable(TrackSource.Status.REQUIRES_PROFILE_SEARCH);
    }

    /** One file first, then a plain playlist, then the encrypted ones; the best quality of each kind first. */
    private static java.util.List<JSONObject> orderedTranscodings(org.json.JSONArray transcodings) {
        java.util.List<JSONObject> ordered = new java.util.ArrayList<>();
        for (int i = 0; i < transcodings.length(); i++) {
            JSONObject transcoding = transcodings.optJSONObject(i);
            if (transcoding == null) continue;
            JSONObject format = transcoding.optJSONObject("format");
            if (format == null || !format.optString("mime_type").startsWith("audio/")) continue;
            ordered.add(transcoding);
        }
        java.util.Collections.sort(ordered, (left, right) -> rank(left) - rank(right));
        return ordered;
    }

    private static int rank(JSONObject transcoding) {
        JSONObject format = transcoding.optJSONObject("format");
        String protocol = format == null ? "" : format.optString("protocol");
        int kind = "progressive".equals(protocol) ? 0 : "hls".equals(protocol) ? 2 : 4;
        // "hq" and "sq" carry more than "lq", so the low quality copy is left for last.
        return kind + ("lq".equals(transcoding.optString("quality")) ? 1 : 0);
    }

    /** Turns the transcoding endpoint into the URL of the file or playlist behind it. */
    static String resolveStreamUrl(String endpoint) throws Exception {
        if (endpoint == null || endpoint.isEmpty()) return null;
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

    /** @return The response code of an authorized DELETE request to the SoundCloud API. */
    public static int apiDelete(String url) throws Exception {
        HttpURLConnection connection = openApiConnection(url);
        connection.setRequestMethod("DELETE");
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

    /**
     * Keeps the patch within the user's existing free, full-track playback entitlement.
     *
     * @return Why the track is not downloaded, or null if nothing restricts it.
     */
    private static TrackSource.Status restrictionOf(JSONObject track) {
        String policy = track.optString("policy").toUpperCase(Locale.US);
        String monetization = track.optString("monetization_model").toUpperCase(Locale.US);
        if (policy.contains("SNIP")) return TrackSource.Status.AUDIO_ONLY_PREVIEW;
        if (policy.contains("SUB") || monetization.contains("SUB") || monetization.contains("GO_PLUS")) {
            return TrackSource.Status.SUBSCRIPTION_REQUIRED;
        }
        if (policy.contains("BLOCK")) return TrackSource.Status.UNAVAILABLE;
        return null;
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

        rememberDownload(trackId, fileName);
        DownloadProgress.onStarted(trackId, playlistId);

        if (notify) showToast(context, text("Скачивание началось: Музыка/Arsound", "Downloading to Music/Arsound"));
    }

    /** Marks the track as downloaded and remembers which file in Music/Arsound belongs to it. */
    private static void rememberDownload(String trackId, String fileName) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;
        Set<String> tracks = new HashSet<>(getDownloadedTracks());
        tracks.add(trackId);
        preferences.edit()
                .putStringSet(DOWNLOADED_TRACKS, tracks)
                .putString(TRACK_FILE_PREFIX + trackId, fileName)
                .apply();
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
        // An HLS track has no file until its segments are packed.
        if (DownloadProgress.isAssembling(trackId)) return DownloadState.IN_PROGRESS;
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

    /**
     * Deletes the file of a downloaded track and forgets it.
     * <p>
     * Tracks imported from the phone are not touched: they are not downloads, and the user removes
     * them one by one themselves.
     *
     * @return True if the track is no longer downloaded.
     */
    public static boolean deleteDownload(Context context, String trackId) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null || trackId == null) return false;

        String fileName = preferences.getString(TRACK_FILE_PREFIX + trackId, null);
        boolean deleted = true;
        if (fileName != null) deleted = deleteFile(context, fileName);

        Set<String> tracks = new HashSet<>(getDownloadedTracks());
        tracks.remove(trackId);
        preferences.edit()
                .putStringSet(DOWNLOADED_TRACKS, tracks)
                .remove(TRACK_FILE_PREFIX + trackId)
                .apply();
        return deleted;
    }

    /**
     * Since Android 10 a file in Music/Arsound can only be removed through the media store unless the
     * app created it itself, so both ways are tried.
     */
    private static boolean deleteFile(Context context, String fileName) {
        java.io.File file = new java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Arsound/" + fileName);
        if (file.isFile() && file.delete()) return true;

        try {
            int removed = context.getContentResolver().delete(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND "
                            + android.provider.MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{fileName, "%Music/Arsound%"});
            if (removed > 0) return true;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not delete " + fileName + " through the media store: " + ex);
        }
        // Nothing to delete is the same result as a deleted file: the track is not downloaded any more.
        return !file.exists();
    }

    /** Whether the track was downloaded by Arsound, so a "delete" row makes sense for it. */
    public static boolean isDownloaded(String trackId) {
        return trackId != null && getDownloadedTracks().contains(trackId);
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

    static Set<String> getDownloadedTracks() {
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
