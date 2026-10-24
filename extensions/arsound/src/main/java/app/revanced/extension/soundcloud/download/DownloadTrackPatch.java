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
    /** The download manager job of a track whose file is not known to be complete yet. */
    private static final String TRACK_JOB_PREFIX = "track_job_";
    /** The earlier file of a track downloaded again, removed once the new file is complete. */
    private static final String TRACK_REPLACES_PREFIX = "track_replaces_";
    /** Guards the read, change and write of the downloaded set: tracks are added and removed by several threads. */
    private static final Object DOWNLOADS_LOCK = new Object();
    /**
     * Tracks between the "already downloading?" check and the start of their download, which takes a few
     * requests: a second start of the same track in that time would download it twice.
     */
    private static final Set<String> starting = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** HLS tracks deleted while they were assembled: their file is not kept when the assembly ends. */
    private static final Set<String> cancelledAssemblies = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Pattern TRACK_ID = Pattern.compile("(\\d+)$");
    private static final String API_ROOT = "https://api-v2.soundcloud.com";

    /** Read each time: the app language can change while the app runs (the welcome switch). */
    private static boolean russian() {
        return "ru".equals(Locale.getDefault().getLanguage());
    }

    /**
     * The SoundCloud OAuth helper, used to authorize the download request like the app does.
     */
    private static volatile Object oAuth;

    static String text(String russian, String english) {
        return russian() ? russian : english;
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
                app.revanced.extension.soundcloud.recommendations.TrackDislikes.addTrackMenuRow(dialog, trackUrn);
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onTrackMenu failure", ex);
        }
    }

    /**
     * Assembles HLS tracks two at a time. The shared background pool starts a thread for every task, so a
     * whole playlist assembled at once held every segment in memory and ran the app out of heap.
     */
    private static final java.util.concurrent.ExecutorService hlsAssembly =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread thread = new Thread(r, "arsound-hls-assembly");
                thread.setDaemon(true);
                return thread;
            });

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

    /**
     * SoundCloud's own "Download" button of a track ({@link DownloadButtons}). A track downloaded already is not
     * downloaded again by it: "Download file again" in the menu does that on purpose.
     */
    static void downloadFromSoundCloudButton(Context context, String trackId) {
        Context appContext = context.getApplicationContext();
        if (getDownloadedTracks().contains(trackId)) {
            Toast.makeText(appContext, DownloadProgress.isDownloading(trackId)
                            ? text("Этот трек уже скачивается", "This track is already downloading")
                            : text("Трек уже скачан. Заново — «Скачать файл ещё раз» в меню",
                            "Already downloaded. \"Download file again\" in the menu redoes it"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        requestDownload(appContext, trackId);
    }

    private static void requestDownload(Context context, String trackId) {
        Toast.makeText(context, text("Подготавливаю скачивание…", "Preparing download…"), Toast.LENGTH_SHORT).show();

        Utils.runOnBackgroundThread(() -> {
            if (!starting.add(trackId)) {
                showToast(context, text("Этот трек уже скачивается", "This track is already downloading"));
                return;
            }
            try {
                // Also a track downloaded again, which counts as downloaded while its earlier file is there.
                if (isDownloadPending(trackId) || getDownloadState(context, trackId) == DownloadState.IN_PROGRESS) {
                    showToast(context, text("Этот трек уже скачивается", "This track is already downloading"));
                    return;
                }
                TrackSource source = resolveSource(trackId);
                if (!source.isDownloadable()) {
                    showToast(context, unavailableMessage(source.status));
                    return;
                }
                // "Download again" keeps the earlier file until the new one is complete, then replaces it:
                // a download that fails on a bad network does not cost the track its file.
                start(context, trackId, source, true, null);
            } catch (Exception ex) {
                Logger.printException(() -> "Download request failure", ex);
                showToast(context, text("Не удалось скачать трек", "Could not download the track"));
            } finally {
                starting.remove(trackId);
            }
        });
    }

    private static final int RATE_LIMIT_RETRIES = 3;
    /** The shortest pause between two API requests of the app, whichever threads send them. */
    private static final long API_GAP_MS = 120;
    private static long nextApiSlot;

    /** Spaces the requests out, so a playlist of hundreds of tracks does not hit the server all at once. */
    private static void waitForApiSlot() throws InterruptedException {
        long wait;
        synchronized (DownloadTrackPatch.class) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextApiSlot);
            nextApiSlot = slot + API_GAP_MS;
            wait = slot - now;
        }
        if (wait > 0) Thread.sleep(wait);
    }

    /** The pause the server asked for in Retry-After, or a growing one when it did not say. */
    private static long retryAfterMs(HttpURLConnection connection, int attempt) {
        long seconds = 0;
        try {
            String header = connection.getHeaderField("Retry-After");
            if (header != null) seconds = Long.parseLong(header.trim());
        } catch (NumberFormatException ignored) {
            // A date instead of seconds: the growing pause below is good enough.
        }
        long pause = seconds > 0 ? seconds * 1000 : 2000L << attempt;
        return Math.min(pause, 15_000);
    }

    /**
     * @return The response code and body of an authorized GET request to the SoundCloud API.
     */
    public static String[] apiGet(String url) throws Exception {
        HttpURLConnection connection = null;
        int code = 0;
        // HTTP 429 is "too many requests": wait as long as the server asks and ask again, instead of
        // reading it as "this track has no such file".
        for (int attempt = 0; attempt <= RATE_LIMIT_RETRIES; attempt++) {
            waitForApiSlot();
            connection = openApiConnection(url);
            code = connection.getResponseCode();
            if (code != 429 || attempt == RATE_LIMIT_RETRIES) break;
            long pause = retryAfterMs(connection, attempt);
            connection.disconnect();
            Logger.printInfo(() -> "API rate limit for " + url + ", waiting " + pause + " ms");
            Thread.sleep(pause);
        }
        if (code != HttpURLConnection.HTTP_OK) {
            int failed = code;
            Logger.printInfo(() -> "API request failed, HTTP " + failed + " for " + url);
            connection.disconnect();
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
        // Started from the track menu at the same moment: that start downloads it.
        if (!starting.add(trackId)) return true;
        try {
            if (source == null || !source.isFresh()) source = resolveSource(trackId);
            if (!source.isDownloadable()) return false;
            start(context, trackId, source, false, playlistId);
            return true;
        } finally {
            starting.remove(trackId);
        }
    }

    /** Starts the download through the download manager, or through the HLS assembler. */
    private static void start(Context context, String trackId, TrackSource source, boolean notify, String playlistId) {
        String previous = fileNameOf(trackId);
        if (source.status == TrackSource.Status.READY) {
            enqueue(context, trackId, source.url, notify, playlistId, previous);
            return;
        }

        // The segments are downloaded, decrypted and packed by the app itself, which takes CPU time.
        if (!DownloadProgress.tryBeginAssembly(trackId, playlistId)) {
            if (notify) showToast(context, text("Этот трек уже скачивается", "This track is already downloading"));
            return;
        }
        cancelledAssemblies.remove(trackId);
        if (notify) {
            showToast(context, text("Собираю трек из потока, это займёт немного времени…",
                    "Assembling the track from its stream, this takes a moment…"));
        }
        hlsAssembly.execute(() -> {
            String fileName = null;
            try {
                // Without a network every queued track would fail in turn, each after its retries.
                // Deleted while it waited in the queue: nothing to assemble.
                if (!awaitNetwork(context, trackId)) return;
                TrackSource current = freshHlsSource(trackId, source);
                fileName = HlsDownloader.download(context, trackId, current.url, current.mimeType);
                if (fileName == null && !isOnline(context)) {
                    // The network went away during the assembly: the track waits for it and is assembled once more.
                    if (!awaitNetwork(context, trackId) || !isOnline(context)) return;
                    current = freshHlsSource(trackId, current);
                    fileName = HlsDownloader.download(context, trackId, current.url, current.mimeType);
                }
            } catch (Throwable ex) {
                // An error (out of memory on a weak phone) would end the whole app together with the pool thread.
                Logger.printException(() -> "Could not assemble " + trackId, ex);
            } finally {
                if (cancelledAssemblies.remove(trackId)) {
                    // The user deleted the track while it was queued or assembled: no file, and no "failed" either.
                    if (fileName != null) deleteFile(context, fileName);
                } else if (fileName != null) {
                    rememberDownload(trackId, fileName, -1, null);
                    // Downloaded again under another name: the new file is complete, so the earlier one goes.
                    if (previous != null && !previous.equals(fileName)) deleteFile(context, previous);
                    if (notify) showToast(context, text("Трек сохранён: Музыка/Arsound", "Saved to Music/Arsound"));
                } else if (notify) {
                    showToast(context, text("Не удалось собрать трек", "Could not assemble the track"));
                }
                DownloadProgress.onAssemblyFinished(trackId);
            }
        });
    }

    /** How long an HLS link stays good: a track that waited its turn in the assembly queue asks for a new one. */
    private static final long HLS_LINK_FRESH_MS = 60_000;
    /** The longest wait of a queued assembly for the network; after it the track is tried and may fail. */
    private static final long NETWORK_WAIT_MS = 10 * 60_000;

    private static boolean isOnline(Context context) {
        try {
            android.net.ConnectivityManager manager = context.getApplicationContext()
                    .getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
            return capabilities != null && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception ex) {
            // Unknown: the assembly goes ahead and finds out.
            return true;
        }
    }

    /**
     * The end of the wait for the network, shared by the queued assemblies: once it passes with the network still
     * gone, the rest of the queue goes on at once (and fails) instead of each track waiting the whole time again.
     * 0 while the network is there.
     */
    private static volatile long networkWaitUntil;

    /**
     * Waits on the assembly thread until there is a network again, at most until the shared deadline.
     *
     * @return False if the track was deleted meanwhile.
     */
    private static boolean awaitNetwork(Context context, String trackId) throws InterruptedException {
        while (!isOnline(context)) {
            if (cancelledAssemblies.contains(trackId)) return false;
            long now = android.os.SystemClock.elapsedRealtime();
            synchronized (DownloadTrackPatch.class) {
                if (networkWaitUntil == 0) networkWaitUntil = now + NETWORK_WAIT_MS;
            }
            if (now >= networkWaitUntil) break;
            Thread.sleep(2_000);
        }
        if (isOnline(context)) networkWaitUntil = 0;
        return !cancelledAssemblies.contains(trackId);
    }

    private static TrackSource freshHlsSource(String trackId, TrackSource source) {
        if (source.isFresh(HLS_LINK_FRESH_MS)) return source;
        try {
            TrackSource renewed = resolveSource(trackId);
            if (renewed.status == TrackSource.Status.REQUIRES_HLS_PROCESSING && renewed.isDownloadable()) return renewed;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not renew the stream link of " + trackId + ": " + ex);
        }
        return source;
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
        return resolveSource(trackId, null);
    }

    /**
     * @param known The track as the API already returned it (for instance in a list of tracks), or null to
     *              request it. It saves one request, and the author-provided file is only asked for when the
     *              track says it has one: for the others that request always ended with HTTP 404, and a
     *              whole playlist of them ran into the rate limit (HTTP 429).
     */
    static TrackSource resolveSource(String trackId, JSONObject known) throws Exception {
        TrackSource source = TrackSource.unavailable(TrackSource.Status.UNAVAILABLE);
        // An answer that may be different in a minute: the other ways are still tried, and it is thrown only if none
        // of them works, so the track is not taken for unavailable.
        java.io.IOException transientError = null;
        JSONObject track = known;
        if (track == null) {
            String[] trackResponse = apiGet(API_ROOT + "/tracks/" + trackId);
            if (isTransient(trackResponse)) {
                transientError = new java.io.IOException("HTTP " + trackResponse[0]);
            } else if (trackResponse[1] == null) {
                Logger.printInfo(() -> "Could not load stream metadata for " + trackId + ", HTTP " + trackResponse[0]);
            } else {
                track = new JSONObject(trackResponse[1]);
            }
        }

        if (track == null || track.optBoolean("downloadable", true)) {
            String[] directDownload = apiGet(API_ROOT + "/tracks/" + trackId + "/download");
            if (isTransient(directDownload)) {
                transientError = new java.io.IOException("HTTP " + directDownload[0]);
            } else if (directDownload[1] != null) {
                String redirect = new JSONObject(directDownload[1]).optString("redirectUri");
                if (!redirect.isEmpty()) return TrackSource.progressive(redirect);
            }
        }

        if (track != null) {
            try {
                source = sourceOfTrack(trackId, track, DownloadTrackPatch::resolveStreamUrl);
            } catch (app.revanced.extension.soundcloud.network.RegionGuard.BlockedException ex) {
                throw ex;
            } catch (java.io.IOException ex) {
                transientError = ex;
            }
            if (source.isDownloadable()) return source;
        }

        // The app is offered less than the web player: a track that is a preview here can be whole there.
        TrackSource alternative = ClientProfiles.search(trackId);
        if (alternative != null && alternative.isDownloadable()) return alternative;
        if (transientError != null) throw transientError;
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
        java.io.IOException transientError = null;
        // Every quality of a protocol is locked the same way, so one locked playlist settles the protocol.
        java.util.Set<String> lockedProtocols = new java.util.HashSet<>();
        for (JSONObject transcoding : orderedTranscodings(transcodings)) {
            JSONObject format = transcoding.optJSONObject("format");
            String protocol = format == null ? "" : format.optString("protocol");
            String mimeType = format == null ? null : format.optString("mime_type");
            if (lockedProtocols.contains(protocol)) continue;

            String url;
            try {
                url = resolver.resolve(transcoding.optString("url"));
            } catch (app.revanced.extension.soundcloud.network.RegionGuard.BlockedException ex) {
                throw ex;
            } catch (java.io.IOException ex) {
                // A passing failure of one stream: the others may answer; thrown below if none does.
                transientError = ex;
                continue;
            }
            if (url == null) continue;
            if ("progressive".equals(protocol)) return TrackSource.progressive(url);

            // A playlist locked by FairPlay or Widevine hands out its key only to a licence server.
            if (HlsDownloader.isDrmProtected(url)) {
                drmSeen = true;
                lockedProtocols.add(protocol);
                Logger.printInfo(() -> "DRM protected stream for " + trackId + ": " + protocol);
                continue;
            }
            return TrackSource.hls(url, mimeType);
        }

        if (drmSeen) return TrackSource.unavailable(TrackSource.Status.DRM_PROTECTED);
        if (transientError != null) throw transientError;
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
        throwIfTransient(streamResponse);
        if (streamResponse[1] == null) return null;
        String streamUrl = new JSONObject(streamResponse[1]).optString("url");
        return streamUrl.isEmpty() ? null : streamUrl;
    }

    /**
     * A failed answer that may well be different in a minute (rate limit, server error, a token being renewed)
     * is thrown, so it is not taken for "this track has no such file" and remembered as such.
     */
    private static void throwIfTransient(String[] response) throws java.io.IOException {
        if (isTransient(response)) throw new java.io.IOException("HTTP " + response[0]);
    }

    private static boolean isTransient(String[] response) {
        if (response[1] != null) return false;
        String code = response[0];
        return "429".equals(code) || "401".equals(code) || code.startsWith("5");
    }

    /** Sends a JSON body with the given method. Returns the response code. */
    public static int apiSend(String method, String url, String json) throws Exception {
        HttpURLConnection connection = openApiConnection(url);
        try {
            connection.setRequestMethod(method);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    /** @return The response code of an authorized DELETE request to the SoundCloud API. */
    public static int apiDelete(String url) throws Exception {
        HttpURLConnection connection = openApiConnection(url);
        try {
            connection.setRequestMethod("DELETE");
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
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

    /**
     * @param previous The file the track already has, kept until the new file is complete; null if none.
     */
    private static void enqueue(Context context, String trackId, String fileUrl, boolean notify, String playlistId,
                                String previous) {
        Uri uri = Uri.parse(fileUrl);
        String fileName = uri.getLastPathSegment();
        // Author downloads can come from a link without an extension; players then do not recognise the file.
        if (fileName == null || !fileName.contains(".")) fileName = "soundcloud-" + trackId + ".mp3";
        fileName = freeFileName(fileName, trackId);

        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle(fileName)
                // Downloads stay silent, like the rest of the app. Requires DOWNLOAD_WITHOUT_NOTIFICATION.
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "Arsound/" + fileName);

        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long job = manager.enqueue(request);

        rememberDownload(trackId, fileName, job, previous);
        DownloadProgress.onStarted(trackId, playlistId);

        if (notify) showToast(context, text("Скачивание началось: Музыка/Arsound", "Downloading to Music/Arsound"));
    }

    /**
     * A name no file in Music/Arsound has. The download manager renames a file whose name is taken, and the
     * name kept for the track would then point at the other file. exists() also sees the files of an earlier
     * install, which this one cannot read.
     */
    private static String freeFileName(String fileName, String trackId) {
        java.io.File folder = musicFolder();
        if (!new java.io.File(folder, fileName).exists()) return fileName;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int copy = 1; copy < 100; copy++) {
            String candidate = base + "-" + trackId + (copy == 1 ? "" : "-" + copy) + extension;
            if (!new java.io.File(folder, candidate).exists()) return candidate;
        }
        return base + "-" + trackId + "-" + System.currentTimeMillis() + extension;
    }

    /**
     * Marks the track as downloaded and remembers which file in Music/Arsound belongs to it.
     *
     * @param job      The download manager job writing the file, or -1 when the file is already complete.
     * @param previous The earlier file of the track, removed once the new one is complete; null if none.
     */
    private static void rememberDownload(String trackId, String fileName, long job, String previous) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;
        synchronized (DOWNLOADS_LOCK) {
            Set<String> tracks = new HashSet<>(getDownloadedTracks());
            tracks.add(trackId);
            SharedPreferences.Editor editor = preferences.edit()
                    .putStringSet(DOWNLOADED_TRACKS, tracks)
                    .putString(TRACK_FILE_PREFIX + trackId, fileName);
            if (job >= 0) editor.putLong(TRACK_JOB_PREFIX + trackId, job);
            else editor.remove(TRACK_JOB_PREFIX + trackId);
            if (job >= 0 && previous != null && !previous.equals(fileName)) {
                editor.putString(TRACK_REPLACES_PREFIX + trackId, previous);
            } else {
                editor.remove(TRACK_REPLACES_PREFIX + trackId);
            }
            editor.apply();
        }
    }

    /**
     * Settles the downloads the download manager is done with. A complete file replaces the one it was
     * downloaded again for; a failed job is forgotten, so the track is no longer marked as downloaded (or
     * keeps its earlier file), and the download manager removes the partial file.
     */
    static void settleDownloads(Context context) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;
        for (java.util.Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(TRACK_JOB_PREFIX) || !(entry.getValue() instanceof Long)) continue;
            settle(context, entry.getKey().substring(TRACK_JOB_PREFIX.length()), (Long) entry.getValue());
        }
    }

    /** @return True if the job is over (complete or failed), false while it still runs or cannot be asked. */
    private static boolean settle(Context context, String trackId, long job) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null || context == null) return false;
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        int status;
        try (android.database.Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(job))) {
            // A job the download manager no longer has was cleared from its list after it ended.
            status = cursor.moveToFirst() ? cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) : -1;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not ask the download manager about " + trackId + ": " + ex);
            return false;
        }
        if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING
                || status == DownloadManager.STATUS_PAUSED) {
            return false;
        }

        String replaced;
        boolean complete;
        synchronized (DOWNLOADS_LOCK) {
            // Deleted or downloaded again meanwhile: that newer state is the one that counts.
            if (preferences.getLong(TRACK_JOB_PREFIX + trackId, -1) != job) return true;
            String fileName = preferences.getString(TRACK_FILE_PREFIX + trackId, null);
            replaced = preferences.getString(TRACK_REPLACES_PREFIX + trackId, null);
            complete = status == DownloadManager.STATUS_SUCCESSFUL
                    || (status == -1 && fileName != null && musicFile(fileName).length() > 0);
            SharedPreferences.Editor editor = preferences.edit()
                    .remove(TRACK_JOB_PREFIX + trackId)
                    .remove(TRACK_REPLACES_PREFIX + trackId);
            if (!complete) {
                if (replaced != null) {
                    editor.putString(TRACK_FILE_PREFIX + trackId, replaced);
                } else {
                    Set<String> tracks = new HashSet<>(getDownloadedTracks());
                    tracks.remove(trackId);
                    editor.putStringSet(DOWNLOADED_TRACKS, tracks).remove(TRACK_FILE_PREFIX + trackId);
                }
            }
            editor.apply();
        }
        if (complete) {
            if (replaced != null) deleteFile(context, replaced);
        } else {
            int failed = status;
            Logger.printInfo(() -> "Download of " + trackId + " failed, status " + failed);
            try {
                manager.remove(job);
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not remove the failed job of " + trackId + ": " + ex);
            }
        }
        return true;
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
        // A track downloaded again keeps its earlier file until the new one is complete: it stays downloaded.
        if (getDownloadedFile(trackId) != null) return DownloadState.DOWNLOADED;
        // An HLS track has no file until its segments are packed.
        if (DownloadProgress.isAssembling(trackId)) return DownloadState.IN_PROGRESS;
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

        // A download still running is stopped: the download manager removes its partial file itself.
        long job = preferences.getLong(TRACK_JOB_PREFIX + trackId, -1);
        if (job >= 0) {
            try {
                ((DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE)).remove(job);
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not stop the download of " + trackId + ": " + ex);
            }
        }
        // An HLS track being assembled has no file yet; the assembly drops it when it ends.
        if (DownloadProgress.isAssembling(trackId)) cancelledAssemblies.add(trackId);

        String fileName = preferences.getString(TRACK_FILE_PREFIX + trackId, null);
        String replaced = preferences.getString(TRACK_REPLACES_PREFIX + trackId, null);
        if (replaced != null) deleteFile(context, replaced);
        // A file that stays (made before a reinstall, so not this app's own) keeps its record: it still plays,
        // and the settings can have it removed through the system.
        if (fileName != null && !deleteFile(context, fileName)) return false;

        synchronized (DOWNLOADS_LOCK) {
            Set<String> tracks = new HashSet<>(getDownloadedTracks());
            tracks.remove(trackId);
            preferences.edit()
                    .putStringSet(DOWNLOADED_TRACKS, tracks)
                    .remove(TRACK_FILE_PREFIX + trackId)
                    .remove(TRACK_JOB_PREFIX + trackId)
                    .remove(TRACK_REPLACES_PREFIX + trackId)
                    .apply();
        }
        return true;
    }

    /**
     * Since Android 10 a file in Music/Arsound can only be removed through the media store unless the
     * app created it itself, so both ways are tried.
     */
    private static boolean deleteFile(Context context, String storedName) {
        java.io.File file = musicFile(storedName);
        // Early versions saved some files without an extension; such files may have been renamed to ".mp3" since.
        String name = storedName;
        if (!file.exists() && !storedName.contains(".")) {
            file = new java.io.File(file.getPath() + ".mp3");
            name = storedName + ".mp3";
        }
        if (file.isFile() && file.delete()) return true;

        String fileName = name;
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

    private static java.io.File musicFolder() {
        return new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Arsound");
    }

    private static java.io.File musicFile(String fileName) {
        return new java.io.File(musicFolder(), fileName);
    }

    /** The file name kept for a track, or null if none. */
    private static String fileNameOf(String trackId) {
        SharedPreferences preferences = getPreferences();
        return preferences == null ? null : preferences.getString(TRACK_FILE_PREFIX + trackId, null);
    }

    /**
     * The file of a downloaded track as it stands, also one that cannot be read (made before a reinstall):
     * for counting the space and for removing. Null if the file is not known or is gone.
     */
    public static java.io.File knownFile(String trackId) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null || trackId == null) return null;
        String fileName = preferences.getString(TRACK_FILE_PREFIX + trackId, null);
        if (fileName == null) return null;
        java.io.File file = new java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Arsound/" + fileName);
        if (!file.exists() && !fileName.contains(".")) file = new java.io.File(file.getPath() + ".mp3");
        return file.exists() ? file : null;
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

        // The download manager makes the file at its full size at once and fills it as it goes, so a file
        // is complete only once its job ended. Meanwhile the earlier file of a track downloaded again plays.
        long job = preferences.getLong(TRACK_JOB_PREFIX + trackId, -1);
        if (job >= 0 && isJobRunning(trackId, job)) {
            String replaced = preferences.getString(TRACK_REPLACES_PREFIX + trackId, null);
            return replaced == null ? null : readableFile(replaced);
        }
        // Settled just now: a failed job may have given the track back its earlier file, or none.
        if (job >= 0) {
            fileName = preferences.getString(TRACK_FILE_PREFIX + trackId, null);
            if (fileName == null) return null;
        }
        return readableFile(fileName);
    }

    /** When each running job was last found running, so lists that ask for many files do not ask the system each time. */
    private static final java.util.Map<String, Long> runningCheckedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RUNNING_CHECK_MS = 2_000;

    /**
     * Whether the download manager still writes the file of the track. Asked of the download manager itself, not
     * of the set of downloads the screen shows: that one is only brought up to date while the app is open.
     */
    private static boolean isJobRunning(String trackId, long job) {
        Long checkedAt = runningCheckedAt.get(trackId);
        long now = android.os.SystemClock.elapsedRealtime();
        if (checkedAt != null && now - checkedAt < RUNNING_CHECK_MS) return true;
        if (settle(Utils.getContext(), trackId, job)) {
            runningCheckedAt.remove(trackId);
            return false;
        }
        runningCheckedAt.put(trackId, now);
        return true;
    }

    /** Whether a download of the track was started and is not over yet: a second one must not start. */
    private static boolean isDownloadPending(String trackId) {
        if (DownloadProgress.isAssembling(trackId)) return true;
        SharedPreferences preferences = getPreferences();
        long job = preferences == null ? -1 : preferences.getLong(TRACK_JOB_PREFIX + trackId, -1);
        return job >= 0 && isJobRunning(trackId, job);
    }

    private static java.io.File readableFile(String fileName) {
        java.io.File file = musicFile(fileName);
        // Early versions saved some files without an extension; such files may have been renamed to ".mp3" since.
        if (!file.isFile() && !fileName.contains(".")) {
            java.io.File withExtension = new java.io.File(file.getPath() + ".mp3");
            if (withExtension.isFile()) file = withExtension;
        }
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
