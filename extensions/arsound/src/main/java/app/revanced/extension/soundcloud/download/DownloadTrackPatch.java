package app.revanced.extension.soundcloud.download;

import android.app.DownloadManager;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
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
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.BatchedPreferences;
import app.revanced.extension.soundcloud.shared.Getters;
import app.revanced.extension.soundcloud.shared.SerialWork;

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
    /** Guards the reading and dropping of {@link #downloadedSnapshot}, so a stale read is never kept after a write. */
    private static final Object DOWNLOADED_LOCK = new Object();
    /** What is done with the files of finished downloads, one after another. */
    private static final SerialWork SETTLE_WORK = new SerialWork("Arsound settle");
    private static volatile Set<String> downloadedSnapshot;
    /**
     * Maps a track id to the file name in its download location. Downloads made before this key existed have no
     * entry. The row of the file in the media store and the location are kept next to it, see {@link DownloadIndex}.
     */
    static final String TRACK_FILE_PREFIX = "track_file_";
    /** The download manager job of a track whose file is not known to be complete yet. */
    private static final String TRACK_JOB_PREFIX = "track_job_";
    /** The earlier file of a track downloaded again, removed once the new file is complete. */
    private static final String TRACK_REPLACES_PREFIX = "track_replaces_";
    /** The location and the media store row of the earlier file, kept next to its name. */
    private static final String TRACK_REPLACES_DIR_PREFIX = "track_replaces_dir_";
    private static final String TRACK_REPLACES_URI_PREFIX = "track_replaces_uri_";
    private static final String TRACK_REPLACES_READ_PREFIX = "track_replaces_read_";
    /**
     * Tracks the user deleted, whose copies may still be among the files of the folder: a look for files the app has
     * no record of leaves them alone, see {@link DownloadIndex#adopt}.
     */
    private static final String DELETED_TRACKS = "adopt_skip";
    /** The title and artist of a track whose file is still downloading, for tagging it in the media store when complete. */
    private static final String TRACK_TITLE_PREFIX = "track_title_";
    private static final String TRACK_ARTIST_PREFIX = "track_artist_";
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

    public static String text(String russian, String english) {
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
     * Downloads the tracks the app fetches itself two at a time: HLS tracks, which are assembled, and single files
     * for a folder the download manager cannot write to. The shared background pool starts a thread for every task, so a
     * whole playlist assembled at once held every segment in memory and ran the app out of heap.
     */
    private static final java.util.concurrent.ExecutorService ownDownloads =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread thread = new Thread(r, "arsound-own-download");
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
            Object trackUrn = Getters.get(trackItem, "getUrn");
            String trackId = parseTrackId(trackUrn);
            if (trackId == null) return icon;
            if (getDownloadedTracks().contains(trackId)) {
                return iconState(DownloadProgress.isDownloading(trackId) ? "DOWNLOADING" : "DOWNLOADED");
            }
            // A DRM track lives in SoundCloud's offline library, not as an Arsound file, but the user wants it to
            // look the same: show it the same downloaded (or downloading) icon from SoundCloud's own offline state.
            String nativeState = app.revanced.extension.soundcloud.offline.NativeOffline.offlineState(trackId);
            if ("DOWNLOADED".equals(nativeState)) return iconState("DOWNLOADED");
            if ("REQUESTED".equals(nativeState) || "DOWNLOADING".equals(nativeState)) return iconState("DOWNLOADING");
            return icon;
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
                        // A file in a folder that is gone (a card taken out) is not "left by an earlier install".
                        String folder = deleted ? null : DownloadIndex.unreachableFolder(appContext, trackId);
                        showToast(appContext, deleted
                                ? text("Файл удалён", "The file is deleted")
                                : folder != null
                                ? text("Папка «" + folder + "» недоступна. Верните карту или забудьте такие треки в настройках, "
                                        + "в разделе «Память».",
                                "The folder " + folder + " is not available. Put the card back, or forget such tracks in the "
                                        + "settings, in Storage.")
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
                // A DRM track (Arsound cannot get its key) or a subscription track goes to SoundCloud's own offline
                // download instead of Arsound's file downloader.
                if (goesToNativeOffline(source.status)) {
                    app.revanced.extension.soundcloud.offline.NativeOffline.makeAvailable(context, trackId);
                    return;
                }
                if (!source.isDownloadable()) {
                    if (RestrictionNote.isRestriction(source.status)) {
                        // A track no app can download: a window that says so plainly, not a toast.
                        TrackSource.Status status = source.status;
                        Utils.runOnMainThread(() -> RestrictionNote.show(context, status));
                    } else {
                        showToast(context, unavailableMessage(source.status));
                    }
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
        // Started from the track menu at the same moment: that start downloads it, and its outcome is this one's. Taken
        // for started before it ended, a start that then failed marked the playlist downloaded.
        if (!starting.add(trackId)) return awaitOtherStart(trackId);
        try {
            if (source == null || !source.isFresh()) source = resolveSource(trackId);
            // A DRM or subscription track is handed to SoundCloud's own offline download; Arsound cannot save it.
            if (goesToNativeOffline(source.status)) {
                app.revanced.extension.soundcloud.offline.NativeOffline.makeAvailable(context, trackId);
                return true;
            }
            if (!source.isDownloadable()) return false;
            start(context, trackId, source, false, playlistId);
            return true;
        } finally {
            starting.remove(trackId);
        }
    }

    /** Waits for the start of the track made elsewhere; whether the track is downloading or downloaded after it. */
    private static boolean awaitOtherStart(String trackId) throws InterruptedException {
        long until = android.os.SystemClock.elapsedRealtime() + 60_000;
        while (starting.contains(trackId) && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(200);
        return isDownloaded(trackId) || DownloadProgress.isDownloading(trackId) || DownloadProgress.isAssembling(trackId);
    }

    /**
     * Starts the download through the download manager, or through the downloader of the app itself: the HLS assembler,
     * and for a folder the download manager cannot write to the downloader of a single file as well.
     */
    private static void start(Context context, String trackId, TrackSource source, boolean notify, String playlistId) {
        DownloadIndex.Ref previous = fileRefOf(trackId);
        // Read once: a download that is running when the choice of the folder changes ends where it started.
        DownloadLocation location = DownloadLocation.forNewDownload(context);
        boolean singleFile = source.status == TrackSource.Status.READY;
        if (singleFile && location.usesDownloadManager()) {
            enqueue(context, trackId, source, notify, playlistId, previous, location);
            return;
        }

        // The file is downloaded (HLS: decrypted and packed) by the app itself, which takes time and CPU.
        if (!DownloadProgress.tryBeginAssembly(trackId, playlistId)) {
            if (notify) showToast(context, text("Этот трек уже скачивается", "This track is already downloading"));
            return;
        }
        cancelledAssemblies.remove(trackId);
        if (notify) {
            showToast(context, singleFile
                    ? text("Скачиваю трек в «" + location.label() + "»…", "Downloading the track to " + location.label() + "…")
                    : text("Собираю трек из потока, это займёт немного времени…",
                    "Assembling the track from its stream, this takes a moment…"));
        }
        ownDownloads.execute(() -> {
            DownloadIndex.Ref saved = null;
            boolean noSpace = false;
            // The folder this download goes to, looked at again when its turn comes.
            DownloadLocation target = location;
            // Deleting the track while it is assembled stops the assembly at the next segment.
            java.util.function.BooleanSupplier cancelled = () -> cancelledAssemblies.contains(trackId);
            try {
                // Without a network every queued track would fail in turn, each after its retries.
                // Deleted while it waited in the queue: nothing to assemble.
                if (!awaitNetwork(context, trackId)) return;
                TrackSource current = freshSource(trackId, source);
                // A track of a playlist can wait in the queue for minutes: the card may be out by now, and without this
                // every track still queued would fail on the folder that was read when the playlist was queued.
                target = stillUsable(context, location);
                saved = download(context, trackId, current, target, cancelled);
                if (saved == null && !isOnline(context)) {
                    // The network went away during the assembly: the track waits for it and is assembled once more.
                    if (!awaitNetwork(context, trackId) || !isOnline(context)) return;
                    current = freshSource(trackId, current);
                    target = stillUsable(context, location);
                    saved = download(context, trackId, current, target, cancelled);
                }
            } catch (HlsDownloader.NoSpaceException ex) {
                noSpace = true;
                showNoSpace(context, target);
            } catch (Throwable ex) {
                // An error (out of memory on a weak phone) would end the whole app together with the pool thread.
                Logger.printException(() -> "Could not assemble " + trackId, ex);
            } finally {
                if (cancelledAssemblies.remove(trackId)) {
                    // The user deleted the track while it was queued or assembled: no file, and no "failed" either.
                    if (saved != null) DownloadIndex.delete(context, saved);
                } else if (saved != null) {
                    rememberDownload(trackId, saved, -1, null);
                    // Downloaded again under another name: the new file is complete, so the earlier one goes.
                    if (previous != null && !previous.sameFileAs(saved)) deleteReplaced(context, previous);
                    if (notify) {
                        showToast(context, text("Трек сохранён: ", "Saved to ") + saved.location().label());
                    }
                } else if (notify && !noSpace) {
                    showToast(context, text("Не удалось собрать трек", "Could not assemble the track"));
                } else if (!notify && !noSpace) {
                    // A playlist starts its tracks quietly: one toast for all of them is shown when the queue is empty.
                    quietFailures.incrementAndGet();
                }
                DownloadProgress.onAssemblyFinished(trackId);
                if (!DownloadProgress.isAnyAssembling()) tellQuietFailures(context);
            }
        });
    }

    /** Tracks that were started without a message of their own and could not be saved, since the last summary. */
    private static final java.util.concurrent.atomic.AtomicInteger quietFailures = new java.util.concurrent.atomic.AtomicInteger();

    private static void tellQuietFailures(Context context) {
        int failed = quietFailures.getAndSet(0);
        if (failed == 0) return;
        showToast(context, text("Не удалось сохранить треков: " + failed, "Could not save " + failed + " tracks"));
    }

    /**
     * The folder a queued download is written to: the one it was queued for, or the folder new downloads go to now when
     * that one is gone (it tells the user, once a minute at most).
     */
    private static DownloadLocation stillUsable(Context context, DownloadLocation queuedFor) {
        return queuedFor.isAvailable(context) ? queuedFor : DownloadLocation.forNewDownload(context);
    }

    /** Downloads the file of a track by the app itself, and saves it into the location. */
    private static DownloadIndex.Ref download(Context context, String trackId, TrackSource source, DownloadLocation location,
                                              java.util.function.BooleanSupplier cancelled) throws HlsDownloader.NoSpaceException {
        return source.status == TrackSource.Status.READY
                ? ProgressiveDownloader.download(context, trackId, source, location, cancelled)
                : HlsDownloader.download(context, trackId, source, location, cancelled);
    }

    /** When the toast about a full storage was last shown. */
    private static volatile long noSpaceToastAt;

    /**
     * A storage that is full fails every track of a playlist, quietly: the toast tells the user why, once a
     * minute at most so that a playlist of hundreds does not stack one for each.
     */
    private static void showNoSpace(Context context, DownloadLocation location) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (noSpaceToastAt != 0 && now - noSpaceToastAt < 60_000) return;
        noSpaceToastAt = now;
        // A card has its own room, apart from the phone's.
        showToast(context, location.usesDownloadManager()
                ? text("Недостаточно места на телефоне, трек не сохранён",
                "Not enough free space on the phone, the track was not saved")
                : text("Недостаточно места на телефоне или в «" + location.label() + "», трек не сохранён",
                "Not enough free space on the phone or in " + location.label() + ", the track was not saved"));
    }

    /** How long a stream link stays good: a track that waited its turn in the assembly queue asks for a new one. */
    private static final long HLS_LINK_FRESH_MS = 60_000;
    /** The longest wait of a queued assembly for the network; after it the track is tried and may fail. */
    private static final long NETWORK_WAIT_MS = 10 * 60_000;

    private static boolean isOnline(Context context) {
        try {
            android.net.ConnectivityManager manager = context.getApplicationContext()
                    .getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
            boolean online = capabilities != null && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            // The network is back: the next outage gets a wait of its own, not what is left of the last one's.
            if (online) networkWaitUntil = 0;
            return online;
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
                // A deadline that ran out longer ago than a whole wait belongs to an outage that ended since, while no
                // assembly was there to see the network come back.
                if (networkWaitUntil == 0 || now - networkWaitUntil > NETWORK_WAIT_MS) networkWaitUntil = now + NETWORK_WAIT_MS;
            }
            if (now >= networkWaitUntil) break;
            Thread.sleep(2_000);
        }
        if (isOnline(context)) networkWaitUntil = 0;
        return !cancelledAssemblies.contains(trackId);
    }

    /** The same source with a link that is not stale; the source itself if no new link can be had. */
    private static TrackSource freshSource(String trackId, TrackSource source) {
        if (source.isFresh(HLS_LINK_FRESH_MS)) return source;
        try {
            TrackSource renewed = resolveSource(trackId);
            if (renewed.status == source.status && renewed.isDownloadable()) return renewed;
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
                if (!redirect.isEmpty()) return TrackSource.progressive(redirect).withTrack(track);
            }
        }

        if (track != null) {
            try {
                source = sourceOfTrack(trackId, track, DownloadTrackPatch::resolveStreamUrl).withTrack(track);
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
        // A DRM track is not saved by Arsound's own downloader (it cannot get the Widevine/FairPlay key); it stays
        // DRM_PROTECTED so the caller hands it to SoundCloud's native offline download instead.
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
        String url = null;
        String mimeType = null;
        for (JSONObject transcoding : orderedTranscodings(transcodings)) {
            JSONObject format = transcoding.optJSONObject("format");
            String protocol = format == null ? "" : format.optString("protocol");
            mimeType = format == null ? null : format.optString("mime_type");
            if (lockedProtocols.contains(protocol)) continue;

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

            // A playlist locked by FairPlay or Widevine hands out its key only to a licence server: it is not saved.
            try {
                if (HlsDownloader.isDrmProtected(url)) {
                    drmSeen = true;
                    lockedProtocols.add(protocol);
                    Logger.printInfo(() -> "DRM protected stream for " + trackId + ": " + protocol);
                    continue;
                }
            } catch (app.revanced.extension.soundcloud.network.RegionGuard.BlockedException ex) {
                throw ex;
            } catch (java.io.IOException ex) {
                // A playlist that could not be inspected may work on the next transcoding or retry.
                transientError = ex;
                continue;
            }
            return TrackSource.hls(url, mimeType);
        }

        // Only DRM-locked playlists were offered: this client cannot decrypt them itself (the Widevine/FairPlay
        // key comes from a licence server). Such a track is routed to SoundCloud's own offline download instead.
        if (drmSeen) {
            return TrackSource.unavailable(TrackSource.Status.DRM_PROTECTED);
        }
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
    /**
     * Whether a track Arsound cannot save as a file is handed to SoundCloud's own offline download instead: a DRM
     * track, whose key comes from a licence server, but whose full audio the server still serves to a free account.
     * <p>
     * Subscription (Go/Go+) tracks are NOT handed over: for a free account the server serves them only as a short
     * preview (policy SNIP → {@link TrackSource.Status#AUDIO_ONLY_PREVIEW}) or refuses the full stream, so there is
     * no full audio to download by any route — even SoundCloud's own offline download filters such tracks out.
     */
    public static boolean goesToNativeOffline(TrackSource.Status status) {
        return status == TrackSource.Status.DRM_PROTECTED;
    }

    private static TrackSource.Status restrictionOf(JSONObject track) {
        String policy = track.optString("policy").toUpperCase(Locale.US);
        String monetization = track.optString("monetization_model").toUpperCase(Locale.US);
        if (policy.contains("SNIP")) return TrackSource.Status.AUDIO_ONLY_PREVIEW;
        if (policy.contains("SUB") || monetization.contains("SUB") || monetization.contains("GO_PLUS")) {
            return TrackSource.Status.SUBSCRIPTION_REQUIRED;
        }
        if (policy.contains("BLOCK")) return TrackSource.Status.REGION_BLOCKED;
        return null;
    }

    /** The name a single-file track is saved under, before it is made free of the names taken in the location. */
    static String progressiveFileName(String trackId, TrackSource source) {
        String fileName = Uri.parse(source.url).getLastPathSegment();
        // Author downloads can come from a link without an extension; players then do not recognise the file.
        if (fileName == null || !fileName.contains(".")) fileName = "soundcloud-" + trackId + ".mp3";
        // Named after the track, "Artist - Title", with the extension of the file on the server.
        if (source.fileBase != null) fileName = source.fileBase + extensionOf(fileName);
        return fileName;
    }

    /**
     * Downloads a single-file track with Android's download manager, which goes on in the background when the app is
     * closed. Only for a location the download manager can write to.
     *
     * @param previous The file the track already has, kept until the new file is complete; null if none.
     * @param location Read once when the download started, see {@link #start}.
     */
    private static void enqueue(Context context, String trackId, TrackSource source, boolean notify, String playlistId,
                                DownloadIndex.Ref previous, DownloadLocation location) {
        Uri uri = Uri.parse(source.url);
        String fileName = freeFileName(location, progressiveFileName(trackId, source), trackId);

        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle(fileName)
                // Downloads stay silent, like the rest of the app. Requires DOWNLOAD_WITHOUT_NOTIFICATION.
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
        location.setDestination(request, fileName);

        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long job = manager.enqueue(request);

        // Before the job is recorded: a job that is recorded is settled, and the settling looks for these.
        rememberTags(trackId, source);
        // The row of the file is not known until the download manager is done with it, see settle.
        rememberDownload(trackId, new DownloadIndex.Ref(location.id(), fileName, null), job, previous);
        DownloadProgress.onStarted(trackId, playlistId);

        if (notify) showToast(context, text("Скачивание началось: ", "Downloading to ") + location.label());
    }

    /**
     * A name no file in the download location has. The download manager renames a file whose name is taken, and the
     * name kept for the track would then point at the other file. The path also shows the files of an earlier
     * install, which this one cannot read, and the listing of the media store the ones it cannot see by path.
     */
    static String freeFileName(DownloadLocation location, String fileName, String trackId) {
        // Names given to other tracks count too: their files may not exist yet, and a playlist asks for many at once.
        java.util.Map<String, String> taken = trackIdsByFileName();
        if (isFree(location, fileName, trackId, taken)) return fileName;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int copy = 1; copy < 100; copy++) {
            String candidate = base + "-" + trackId + (copy == 1 ? "" : "-" + copy) + extension;
            if (isFree(location, candidate, trackId, taken)) return candidate;
        }
        return base + "-" + trackId + "-" + System.currentTimeMillis() + extension;
    }

    private static boolean isFree(DownloadLocation location, String fileName, String trackId, java.util.Map<String, String> taken) {
        String owner = taken.get(fileName);
        java.io.File file = location.file(fileName);
        return !DownloadIndex.hasFile(new DownloadIndex.Ref(location.id(), fileName, null))
                && (file == null || !file.exists()) && (owner == null || owner.equals(trackId));
    }

    /** The extension of a file name with its dot, or ".mp3" if there is none a player would know. */
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot > 0 ? fileName.substring(dot) : "";
        return extension.matches("\\.[A-Za-z0-9]{1,5}") ? extension : ".mp3";
    }

    /**
     * Keeps the title and artist of a track until the download manager is done with its file: only then does the
     * media store list the file, and {@link MediaTags} can tag it.
     */
    private static void rememberTags(String trackId, TrackSource source) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;
        synchronized (DOWNLOADS_LOCK) {
            SharedPreferences.Editor editor = preferences.edit();
            if (source.title != null && !source.title.isEmpty()) editor.putString(TRACK_TITLE_PREFIX + trackId, source.title);
            else editor.remove(TRACK_TITLE_PREFIX + trackId);
            if (source.artist != null && !source.artist.isEmpty()) editor.putString(TRACK_ARTIST_PREFIX + trackId, source.artist);
            else editor.remove(TRACK_ARTIST_PREFIX + trackId);
            editor.apply();
        }
    }

    /**
     * Marks the track as downloaded and remembers which file belongs to it, and where.
     *
     * @param file     The file with its location and, when known, its media store row.
     * @param job      The download manager job writing the file, or -1 when the file is already complete.
     * @param previous The earlier file of the track, removed once the new one is complete; null if none.
     */
    private static void rememberDownload(String trackId, DownloadIndex.Ref file, long job, DownloadIndex.Ref previous) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;
        synchronized (DOWNLOADS_LOCK) {
            Set<String> tracks = new HashSet<>(getDownloadedTracks());
            tracks.add(trackId);
            SharedPreferences.Editor editor = preferences.edit()
                    .putStringSet(DOWNLOADED_TRACKS, tracks);
            putFileRef(editor, trackId, file);
            // Downloaded again after it was deleted: the track has a file of its own once more.
            markDeleted(editor, trackId, false);
            if (job >= 0) editor.putLong(TRACK_JOB_PREFIX + trackId, job);
            else editor.remove(TRACK_JOB_PREFIX + trackId);
            if (job >= 0 && previous != null && !previous.sameFileAs(file)) {
                editor.putString(TRACK_REPLACES_PREFIX + trackId, previous.name)
                        .putString(TRACK_REPLACES_DIR_PREFIX + trackId, previous.locationId);
                if (previous.uri != null) editor.putString(TRACK_REPLACES_URI_PREFIX + trackId, previous.uri);
                else editor.remove(TRACK_REPLACES_URI_PREFIX + trackId);
                if (previous.readUri != null) editor.putString(TRACK_REPLACES_READ_PREFIX + trackId, previous.readUri);
                else editor.remove(TRACK_REPLACES_READ_PREFIX + trackId);
            } else {
                removeReplaced(editor, trackId);
            }
            editor.apply();
            downloadedChanged();
        }
    }

    private static void putFileRef(SharedPreferences.Editor editor, String trackId, DownloadIndex.Ref file) {
        editor.putString(TRACK_FILE_PREFIX + trackId, file.name)
                .putString(DownloadIndex.TRACK_DIR_PREFIX + trackId, file.locationId);
        if (file.uri != null) editor.putString(DownloadIndex.TRACK_URI_PREFIX + trackId, file.uri);
        else editor.remove(DownloadIndex.TRACK_URI_PREFIX + trackId);
        if (file.readUri != null) editor.putString(DownloadIndex.TRACK_READ_PREFIX + trackId, file.readUri);
        else editor.remove(DownloadIndex.TRACK_READ_PREFIX + trackId);
    }

    private static void removeReplaced(SharedPreferences.Editor editor, String trackId) {
        editor.remove(TRACK_REPLACES_PREFIX + trackId)
                .remove(TRACK_REPLACES_DIR_PREFIX + trackId)
                .remove(TRACK_REPLACES_URI_PREFIX + trackId)
                .remove(TRACK_REPLACES_READ_PREFIX + trackId);
    }

    /** The tracks the user deleted, whose leftover copies are not to be taken over again. */
    static Set<String> deletedTrackIds() {
        SharedPreferences preferences = getPreferences();
        return preferences == null ? new HashSet<>() : new HashSet<>(preferences.getStringSet(DELETED_TRACKS, new HashSet<>()));
    }

    /** Writes down in the editor that a track was deleted by the user, or that it has a file again. */
    private static void markDeleted(SharedPreferences.Editor editor, String trackId, boolean deleted) {
        Set<String> ids = deletedTrackIds();
        if (deleted ? ids.add(trackId) : ids.remove(trackId)) editor.putStringSet(DELETED_TRACKS, ids);
    }

    /**
     * Takes over files the app had no record of (see {@link DownloadIndex#adopt}): each track is marked downloaded
     * and gets the file, its location and its row.
     */
    static void rememberAdopted(Map<String, DownloadLocation.Row> files) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;
        synchronized (DOWNLOADS_LOCK) {
            Set<String> tracks = new HashSet<>(getDownloadedTracks());
            Set<String> deleted = deletedTrackIds();
            SharedPreferences.Editor editor = preferences.edit();
            for (Map.Entry<String, DownloadLocation.Row> entry : files.entrySet()) {
                DownloadLocation.Row row = entry.getValue();
                tracks.add(entry.getKey());
                deleted.remove(entry.getKey());
                putFileRef(editor, entry.getKey(), new DownloadIndex.Ref(row.locationId, row.name, row.uri.toString()));
                editor.remove(TRACK_JOB_PREFIX + entry.getKey());
                removeReplaced(editor, entry.getKey());
            }
            editor.putStringSet(DOWNLOADED_TRACKS, tracks).putStringSet(DELETED_TRACKS, deleted).apply();
            downloadedChanged();
        }
    }

    /** Remembers the media store row of the file of a track, once it is known. */
    static void recordUri(String trackId, String fileName, Uri row) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;
        synchronized (DOWNLOADS_LOCK) {
            // The track may have been downloaded again or deleted since the row was looked for.
            if (!fileName.equals(preferences.getString(TRACK_FILE_PREFIX + trackId, null))) return;
            preferences.edit().putString(DownloadIndex.TRACK_URI_PREFIX + trackId, row.toString()).apply();
        }
    }

    /**
     * Remembers the download manager's own address of a file it wrote. Since Android 10 the row the media store makes of
     * such a file may not open for this app, while the address of the download manager always does.
     */
    private static void recordManagerUri(DownloadManager manager, String trackId, String fileName, long job) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;
        try {
            Uri address = manager.getUriForDownloadedFile(job);
            if (address == null) return;
            synchronized (DOWNLOADS_LOCK) {
                // The track may have been downloaded again or deleted since the job ended.
                if (!fileName.equals(preferences.getString(TRACK_FILE_PREFIX + trackId, null))) return;
                preferences.edit().putString(DownloadIndex.TRACK_READ_PREFIX + trackId, address.toString()).apply();
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not get the address of the download of " + trackId + ": " + ex);
        }
    }

    /** The file kept for a track, as recorded; whether it is there is {@link DownloadIndex}'s to say. Null if none. */
    static DownloadIndex.Ref fileRefOf(String trackId) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null || trackId == null) return null;
        String name = preferences.getString(TRACK_FILE_PREFIX + trackId, null);
        if (name == null) return null;
        return new DownloadIndex.Ref(preferences.getString(DownloadIndex.TRACK_DIR_PREFIX + trackId, DownloadLocation.DEFAULT.id()),
                name, preferences.getString(DownloadIndex.TRACK_URI_PREFIX + trackId, null),
                preferences.getString(DownloadIndex.TRACK_READ_PREFIX + trackId, null));
    }

    /** The earlier file of a track downloaded again, which goes once the new file is complete; null if none. */
    static DownloadIndex.Ref replacedRefOf(String trackId) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null || trackId == null) return null;
        String name = preferences.getString(TRACK_REPLACES_PREFIX + trackId, null);
        if (name == null) return null;
        return new DownloadIndex.Ref(preferences.getString(TRACK_REPLACES_DIR_PREFIX + trackId, DownloadLocation.DEFAULT.id()),
                name, preferences.getString(TRACK_REPLACES_URI_PREFIX + trackId, null),
                preferences.getString(TRACK_REPLACES_READ_PREFIX + trackId, null));
    }

    /** The location of every track's file that was recorded. */
    static Set<String> recordedLocationIds() {
        Set<String> ids = new HashSet<>();
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return ids;
        for (java.util.Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(DownloadIndex.TRACK_DIR_PREFIX) && entry.getValue() instanceof String) {
                ids.add((String) entry.getValue());
            }
        }
        return ids;
    }

    /**
     * The files tracks keep in a location, as rows with the address that was recorded and without a size: what stands in
     * for the listing of a location that cannot be listed now. A file without a recorded address is left out.
     */
    static java.util.List<DownloadLocation.Row> recordedRows(String locationId) {
        java.util.List<DownloadLocation.Row> rows = new java.util.ArrayList<>();
        for (String trackId : getDownloadedTracks()) {
            DownloadIndex.Ref ref = fileRefOf(trackId);
            if (ref == null || ref.uri == null || !locationId.equals(ref.locationId)) continue;
            rows.add(new DownloadLocation.Row(ref.locationId, ref.name, Uri.parse(ref.uri), 0, 0, null));
        }
        return rows;
    }

    /**
     * The names of the files the tracks keep in a location: the file of a track and the one it will replace. Of a folder
     * of the user only these (and the files named by id) are of interest, see {@link DownloadLocation#holdsOtherFiles}.
     */
    static Set<String> recordedNames(String locationId) {
        Set<String> names = new HashSet<>();
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return names;
        Map<String, ?> all = preferences.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;
            String key = entry.getKey();
            String trackId;
            String dirKey;
            if (key.startsWith(TRACK_FILE_PREFIX)) {
                trackId = key.substring(TRACK_FILE_PREFIX.length());
                dirKey = DownloadIndex.TRACK_DIR_PREFIX + trackId;
            } else if (key.startsWith(TRACK_REPLACES_PREFIX) && key.substring(TRACK_REPLACES_PREFIX.length()).matches("\\d+")) {
                // The other keys of the earlier file ("track_replaces_dir_ID") start the same way.
                trackId = key.substring(TRACK_REPLACES_PREFIX.length());
                dirKey = TRACK_REPLACES_DIR_PREFIX + trackId;
            } else {
                continue;
            }
            Object dir = all.get(dirKey);
            if (locationId.equals(dir instanceof String ? dir : DownloadLocation.DEFAULT.id())) names.add((String) entry.getValue());
        }
        return names;
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

    /**
     * Files that could not be removed because their folder was out of reach (a card taken out), as JSON: the location,
     * the name and the addresses. They are removed once the folder is back, see {@link #retryPendingDeletes}.
     */
    private static final String PENDING_DELETES = "pending_deletes";

    /**
     * Removes the earlier file of a track that was downloaded again. When its folder cannot be reached, the track no
     * longer records the file, and it would stay in the folder for ever as a copy the app cannot find any more: it is
     * written down and removed when the folder is back.
     */
    static void deleteReplaced(Context context, DownloadIndex.Ref replaced) {
        if (DownloadIndex.delete(context, replaced)) return;
        // Any other reason (a file of an earlier install) will not change with time: nothing to wait for.
        if (replaced.location().isAvailable(context)) return;
        SharedPreferences preferences = getPreferences();
        String entry = pendingEntry(replaced);
        if (preferences == null || entry == null) return;
        synchronized (DOWNLOADS_LOCK) {
            Set<String> pending = new HashSet<>(preferences.getStringSet(PENDING_DELETES, new HashSet<>()));
            if (pending.add(entry)) preferences.edit().putStringSet(PENDING_DELETES, pending).apply();
        }
        Logger.printInfo(() -> "The folder of " + replaced.name + " cannot be reached, it is removed when it is back");
    }

    private static String pendingEntry(DownloadIndex.Ref ref) {
        try {
            JSONObject json = new JSONObject().put("d", ref.locationId).put("n", ref.name);
            if (ref.uri != null) json.put("u", ref.uri);
            if (ref.readUri != null) json.put("r", ref.readUri);
            return json.toString();
        } catch (org.json.JSONException ex) {
            return null;
        }
    }

    private static DownloadIndex.Ref parsePending(String entry) {
        try {
            JSONObject json = new JSONObject(entry);
            return new DownloadIndex.Ref(json.getString("d"), json.getString("n"), json.optString("u", null),
                    json.optString("r", null));
        } catch (org.json.JSONException ex) {
            return null;
        }
    }

    /** Whether a track keeps this file now (it was downloaded to the same place again), so it is not an extra one. */
    private static boolean isKept(DownloadIndex.Ref file) {
        for (String trackId : getDownloadedTracks()) {
            if (file.sameFileAs(fileRefOf(trackId)) || file.sameFileAs(replacedRefOf(trackId))) return true;
        }
        return false;
    }

    /**
     * Removes the files that waited for their folder to be back, for the folders that are. A file is tried once: if
     * the folder is back and the file still stays, it will not go by waiting longer. Call off the main thread.
     */
    static void retryPendingDeletes(Context context) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null || context == null) return;
        Set<String> waiting = preferences.getStringSet(PENDING_DELETES, null);
        if (waiting == null || waiting.isEmpty()) return;
        Set<String> done = new HashSet<>();
        for (String entry : new HashSet<>(waiting)) {
            DownloadIndex.Ref file = parsePending(entry);
            if (file != null) {
                if (!file.location().isAvailable(context)) continue;
                if (!isKept(file)) DownloadIndex.delete(context, file);
            }
            done.add(entry);
        }
        if (done.isEmpty()) return;
        synchronized (DOWNLOADS_LOCK) {
            Set<String> rest = new HashSet<>(preferences.getStringSet(PENDING_DELETES, new HashSet<>()));
            rest.removeAll(done);
            preferences.edit().putStringSet(PENDING_DELETES, rest).apply();
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

        DownloadIndex.Ref replaced;
        boolean complete;
        DownloadIndex.Ref completed;
        String title;
        String artist;
        synchronized (DOWNLOADS_LOCK) {
            // Deleted or downloaded again meanwhile: that newer state is the one that counts.
            if (preferences.getLong(TRACK_JOB_PREFIX + trackId, -1) != job) return true;
            DownloadIndex.Ref file = fileRefOf(trackId);
            replaced = replacedRefOf(trackId);
            title = preferences.getString(TRACK_TITLE_PREFIX + trackId, null);
            artist = preferences.getString(TRACK_ARTIST_PREFIX + trackId, null);
            complete = status == DownloadManager.STATUS_SUCCESSFUL || (status == -1 && file != null && hasContent(file));
            completed = complete ? file : null;
            SharedPreferences.Editor editor = preferences.edit()
                    .remove(TRACK_JOB_PREFIX + trackId)
                    .remove(TRACK_TITLE_PREFIX + trackId)
                    .remove(TRACK_ARTIST_PREFIX + trackId);
            removeReplaced(editor, trackId);
            if (!complete) {
                if (replaced != null) {
                    putFileRef(editor, trackId, replaced);
                } else {
                    Set<String> tracks = new HashSet<>(getDownloadedTracks());
                    tracks.remove(trackId);
                    editor.putStringSet(DOWNLOADED_TRACKS, tracks)
                            .remove(TRACK_FILE_PREFIX + trackId)
                            .remove(DownloadIndex.TRACK_DIR_PREFIX + trackId)
                            .remove(DownloadIndex.TRACK_URI_PREFIX + trackId)
                            .remove(DownloadIndex.TRACK_READ_PREFIX + trackId);
                }
            }
            editor.apply();
            downloadedChanged();
        }
        if (complete) {
            // The media store is asked off this thread: settling also runs where lists are drawn. One thread for all of
            // them: a return to the app may settle hundreds of downloads at once.
            SETTLE_WORK.execute(() -> {
                if (replaced != null) deleteReplaced(context, replaced);
                if (completed == null) return;
                // The download manager's own address of the file is the one that opens for the app that asked for it.
                recordManagerUri(manager, trackId, completed.name, job);
                // The row of the file is made by a scan that the download manager asks for when it is done. It is
                // the address the file is read and removed by where the path is closed to the app.
                MediaTags.tagSavedFile(context, completed.location(), completed.name, title, artist, row -> {
                    recordUri(trackId, completed.name, row);
                    DownloadIndex.refreshAsync();
                });
            });
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

    public enum DownloadState {NOT_DOWNLOADED, IN_PROGRESS, DOWNLOADED}

    /**
     * Whether a track was already downloaded by Arsound, so it is not downloaded twice.
     * Tracks downloaded before the file name was remembered count as downloaded: their file cannot be checked.
     * Read by the lists on the main thread for every cell, so it only looks at what the app already knows.
     */
    public static DownloadState getDownloadState(Context context, String trackId) {
        // A track downloaded again keeps its earlier file until the new one is complete: it stays downloaded.
        if (downloadedEntry(trackId) != null) return DownloadState.DOWNLOADED;
        // An HLS track has no file until its segments are packed.
        if (DownloadProgress.isAssembling(trackId)) return DownloadState.IN_PROGRESS;
        // A DRM track downloaded through SoundCloud's own offline library counts as downloaded like any other.
        String nativeState = app.revanced.extension.soundcloud.offline.NativeOffline.offlineState(trackId);
        if ("DOWNLOADED".equals(nativeState)) return DownloadState.DOWNLOADED;
        if ("REQUESTED".equals(nativeState) || "DOWNLOADING".equals(nativeState)) return DownloadState.IN_PROGRESS;
        if (!getDownloadedTracks().contains(trackId)) return DownloadState.NOT_DOWNLOADED;

        SharedPreferences preferences = getPreferences();
        String fileName = preferences == null ? null : preferences.getString(TRACK_FILE_PREFIX + trackId, null);
        if (fileName == null) return DownloadState.DOWNLOADED;

        // Marked, but the file is not complete: either still downloading, or the file was deleted. The running
        // downloads are the ones DownloadProgress last read from the download manager (every second and a half while
        // the app is open): asking the download manager here cost a call into the system per track of a list.
        return DownloadProgress.isDownloading(trackId) ? DownloadState.IN_PROGRESS : DownloadState.NOT_DOWNLOADED;
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

        DownloadIndex.Ref file = fileRefOf(trackId);
        DownloadIndex.Ref replaced = replacedRefOf(trackId);
        if (replaced != null) deleteReplaced(context, replaced);
        // A file that stays (made before a reinstall, so not this app's own) keeps its record: it still plays,
        // and the settings can have it removed through the system.
        if (file != null && !DownloadIndex.delete(context, file)) return false;
        // Downloading the same tracks again and again left copies of them; they must not become the track again.
        DownloadIndex.deleteCopies(context, trackId);

        dropRecord(preferences, trackId, true);
        return true;
    }

    /**
     * Forgets a downloaded track without touching its files: for a track in a folder that is gone for good, where the
     * files cannot be removed and the record would stay for ever.
     *
     * @return True if the record was dropped.
     */
    static boolean forgetDownload(String trackId) {
        return trackId != null && forgetDownloads(java.util.Collections.singletonList(trackId)) == 1;
    }

    /**
     * The same for many tracks at once, in one change of the records: one at a time copied the whole set of downloads
     * for each track.
     *
     * @return How many records were dropped.
     */
    static int forgetDownloads(java.util.Collection<String> trackIds) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null || trackIds.isEmpty()) return 0;
        dropRecords(preferences, trackIds, false);
        return trackIds.size();
    }

    /**
     * @param deleted True if the user deleted the track, so the copies of it that are left are not taken over again.
     */
    private static void dropRecord(SharedPreferences preferences, String trackId, boolean deleted) {
        dropRecords(preferences, java.util.Collections.singletonList(trackId), deleted);
    }

    private static void dropRecords(SharedPreferences preferences, java.util.Collection<String> trackIds, boolean deleted) {
        synchronized (DOWNLOADS_LOCK) {
            Set<String> tracks = new HashSet<>(getDownloadedTracks());
            SharedPreferences.Editor editor = preferences.edit();
            for (String trackId : trackIds) {
                tracks.remove(trackId);
                editor.remove(TRACK_FILE_PREFIX + trackId)
                        .remove(DownloadIndex.TRACK_DIR_PREFIX + trackId)
                        .remove(DownloadIndex.TRACK_URI_PREFIX + trackId)
                        .remove(DownloadIndex.TRACK_READ_PREFIX + trackId)
                        .remove(TRACK_JOB_PREFIX + trackId)
                        .remove(TRACK_TITLE_PREFIX + trackId)
                        .remove(TRACK_ARTIST_PREFIX + trackId);
                removeReplaced(editor, trackId);
                if (deleted) markDeleted(editor, trackId, true);
            }
            editor.putStringSet(DOWNLOADED_TRACKS, tracks);
            editor.apply();
            downloadedChanged();
        }
    }

    /** Whether the file of a finished download has any content. */
    private static boolean hasContent(DownloadIndex.Ref file) {
        DownloadIndex.Entry entry = DownloadIndex.known(file);
        return entry != null && entry.size() > 0;
    }

    /** Whether the track was downloaded by Arsound, so a "delete" row makes sense for it. */
    public static boolean isDownloaded(String trackId) {
        return trackId != null && getDownloadedTracks().contains(trackId);
    }

    /**
     * The file of a downloaded track that the app can use now, or null if the track was not downloaded, is still
     * downloading, or its file was deleted.
     */
    public static DownloadIndex.Entry downloadedEntry(String trackId) {
        return downloadedEntry(trackId, false);
    }

    private static DownloadIndex.Entry downloadedEntry(String trackId, boolean forPlayback) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null || trackId == null) return null;
        DownloadIndex.Ref file = fileRefOf(trackId);
        if (file == null) return null;

        // The download manager makes the file at its full size at once and fills it as it goes, so a file
        // is complete only once its job ended. Meanwhile the earlier file of a track downloaded again plays.
        long job = preferences.getLong(TRACK_JOB_PREFIX + trackId, -1);
        if (job >= 0 && isJobRunning(trackId, job)) {
            file = replacedRefOf(trackId);
            if (file == null) return null;
        } else if (job >= 0) {
            // Settled just now: a failed job may have given the track back its earlier file, or none.
            file = fileRefOf(trackId);
            if (file == null) return null;
        }
        return forPlayback ? DownloadIndex.resolveForPlayback(trackId, file) : DownloadIndex.resolve(trackId, file);
    }

    /**
     * What the player is given to play a downloaded track: the path of its file, or the address of its row in the
     * media store where the path is closed to the app (a file in Music since Android 10). A streamed track fetched
     * ahead while the one before it played ({@link PrefetchedTracks}) plays from its copy the same way.
     * May wait for the system, so it is for the threads of the player, not for the main thread.
     *
     * @return The source, or null if the track is not downloaded or the file cannot be opened: it is streamed then.
     */
    public static String playableSource(String trackId) {
        DownloadIndex.Entry entry = downloadedEntry(trackId, true);
        Context context = Utils.getContext();
        String downloaded = entry == null || context == null ? null : entry.playable(context);
        return downloaded != null ? downloaded : PrefetchedTracks.playable(trackId);
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
    static boolean isDownloadPending(String trackId) {
        if (DownloadProgress.isAssembling(trackId)) return true;
        SharedPreferences preferences = getPreferences();
        long job = preferences == null ? -1 : preferences.getLong(TRACK_JOB_PREFIX + trackId, -1);
        return job >= 0 && isJobRunning(trackId, job);
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
        // These were saved before locations existed, so they are in the default one.
        String location = DownloadLocation.DEFAULT.id();

        int found = 0;
        for (String trackId : getDownloadedTracks()) {
            if (preferences.contains(TRACK_FILE_PREFIX + trackId)) continue;
            try {
                String url = resolveDownloadUrl(trackId);
                String name = url == null ? null : Uri.parse(url).getLastPathSegment();
                if (name == null) continue;
                if (!DownloadIndex.hasFile(new DownloadIndex.Ref(location, name, null))) {
                    String alternative = name.replaceFirst("(\\.[^.]+)$", "-1$1");
                    if (!DownloadIndex.hasFile(new DownloadIndex.Ref(location, alternative, null))) continue;
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

    /** File names of the downloads mapped to their track ids. */
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

    /**
     * The ids of the downloaded tracks: one set read once and kept until it changes, not to be changed by the caller.
     * Every track cell of every list asks for it, and the preferences hand out a new copy of the whole set on every
     * read.
     */
    static Set<String> getDownloadedTracks() {
        Set<String> snapshot = downloadedSnapshot;
        if (snapshot != null) return snapshot;
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return java.util.Collections.emptySet();
        synchronized (DOWNLOADED_LOCK) {
            if (downloadedSnapshot != null) return downloadedSnapshot;
            snapshot = java.util.Collections.unmodifiableSet(
                    new HashSet<>(preferences.getStringSet(DOWNLOADED_TRACKS, java.util.Collections.emptySet())));
            downloadedSnapshot = snapshot;
            return snapshot;
        }
    }

    /** The set of downloaded tracks was written: the next read takes it again. Call after the write. */
    private static void downloadedChanged() {
        synchronized (DOWNLOADED_LOCK) {
            downloadedSnapshot = null;
        }
    }

    /**
     * The per-track records, written to the disk together at most every two seconds: Android writes the whole file
     * (five keys per track) on every change, and a download writes several changes per track.
     */
    private static SharedPreferences getPreferences() {
        Context context = Utils.getContext();
        return context == null ? null
                : BatchedPreferences.of(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE));
    }

    public static void showToast(Context context, String message) {
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


