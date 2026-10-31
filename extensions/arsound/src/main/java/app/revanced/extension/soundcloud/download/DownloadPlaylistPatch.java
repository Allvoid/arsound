package app.revanced.extension.soundcloud.download;

import static app.revanced.extension.soundcloud.download.DownloadTrackPatch.text;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;

/**
 * Adds a "Check downloads" row to the playlist and album menu.
 * It finds playable, non-subscription tracks and offers to download them all. Each task prefers
 * the author-provided file, then falls back to the official progressive stream.
 * <p>
 * Playlists downloaded this way are remembered on this device: the Downloads screen lists them until
 * their downloaded tracks are deleted.
 */
@SuppressWarnings("unused")
public final class DownloadPlaylistPatch {
    private static final String ROW_TAG = "arsound_playlist_download_row";
    private static final String DELETE_ROW_TAG = "arsound_playlist_delete_row";
    private static final Pattern PLAYLIST_ID = Pattern.compile("^soundcloud:playlists:(\\d+)$");
    private static final int TRACKS_PER_REQUEST = 50;
    /** File links resolved at the same time during a check. */
    private static final int PARALLEL_CHECKS = 4;
    /** Tracks without an answer are asked again this many times, after these pauses, and then a few at a time. */
    private static final long[] RETRY_PAUSES_MS = {15_000, 45_000, 90_000};
    private static final int RETRY_PARALLEL_CHECKS = 2;
    private static final String PREFERENCES_NAME = "arsound_downloaded_playlists";

    private static final class TrackInfo {
        final String id;
        final String title;
        /** The source found by the check. Stream links expire, so a stale one is resolved again. */
        final TrackSource source;

        TrackInfo(String id, String title, TrackSource source) {
            this.id = id;
            this.title = title;
            this.source = source;
        }

        /** Whether the track is packed by the app instead of the download manager. */
        boolean needsAssembly() {
            return source.status == TrackSource.Status.REQUIRES_HLS_PROCESSING;
        }
    }

    // region Downloaded playlists

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** @return The ids of the downloaded playlists and the time each was downloaded. */
    public static Map<String, Long> getDownloadedPlaylists() {
        Map<String, Long> result = new HashMap<>();
        SharedPreferences preferences = preferences();
        if (preferences == null) return result;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getValue() instanceof Long) result.put(entry.getKey(), (Long) entry.getValue());
        }
        return result;
    }

    public static boolean isPlaylistDownloaded(String playlistId) {
        SharedPreferences preferences = preferences();
        return playlistId != null && preferences != null && preferences.contains(playlistId);
    }

    private static void setPlaylistDownloaded(String playlistId, boolean downloaded) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        if (downloaded == preferences.contains(playlistId)) return;
        if (downloaded) {
            preferences.edit().putLong(playlistId, System.currentTimeMillis()).apply();
        } else {
            preferences.edit().remove(playlistId).apply();
        }
        Logger.printInfo(() -> "Playlist " + playlistId + (downloaded ? " is downloaded" : " is no longer downloaded"));
        DownloadsScreen.refresh();
    }

    // endregion

    /**
     * Injection point. Called when the playlist menu receives its data.
     *
     * @param menuData The menu data, which holds the header with the playlist urn.
     */
    public static void onPlaylistMenu(Dialog dialog, Object menuData) {
        try {
            String playlistId = findPlaylistId(menuData);
            if (playlistId == null) return;

            // The menu items are recreated right after this call, so add the row once they are in place.
            dialog.getWindow().getDecorView().post(() -> {
                // Run later, outside the try above: a failure here must not reach SoundCloud's menu.
                try {
                    addRow(dialog, playlistId);
                    app.revanced.extension.soundcloud.local.LocalAdditions.addPlaylistMenuRow(dialog, "soundcloud:playlists:" + playlistId, menuData);
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not add the rows to the playlist menu", ex);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onPlaylistMenu failure", ex);
        }
    }

    /**
     * The header of the menu data holds the playlist urn. Field names are obfuscated, so they are found by type.
     */
    private static String findPlaylistId(Object menuData) throws IllegalAccessException {
        for (Object header : fieldValues(menuData)) {
            if (header == null || !header.getClass().getName().contains("BottomSheetHeader")) continue;

            for (Object value : fieldValues(header)) {
                if (value == null) continue;
                Matcher matcher = PLAYLIST_ID.matcher(value.toString());
                if (matcher.find()) return matcher.group(1);
            }
        }
        return null;
    }

    private static List<Object> fieldValues(Object instance) throws IllegalAccessException {
        List<Object> values = new ArrayList<>();
        for (Field field : instance.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            values.add(field.get(instance));
        }
        return values;
    }

    private static void addRow(Dialog dialog, String playlistId) {
        LinearLayout menuItems = dialog.findViewById(
                Utils.getResourceIdentifier(ResourceType.ID, "playlistBottomSheetMenuItems"));
        if (menuItems == null || menuItems.findViewWithTag(ROW_TAG) != null) return;

        Context context = dialog.getContext();
        ViewGroup row = DownloadTrackPatch.createConstraintLayout(context);
        row.setTag(ROW_TAG);
        row.setMinimumHeight(DownloadTrackPatch.dimen(context, "action_list_default_height"));
        LayoutInflater.from(context).inflate(
                Utils.getResourceIdentifier(ResourceType.LAYOUT, "layout_action_list_item"), row, true);

        TextView title = row.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "action_list_item_title"));
        ImageView icon = row.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "action_list_item_icon_start"));
        DownloadTrackPatch.hide(row, "action_list_item_download_icon");
        DownloadTrackPatch.hide(row, "action_list_item_icon_end");
        DownloadTrackPatch.hide(row, "action_list_selectable_check_icon");

        title.setText(text("Проверить скачивание треков", "Check track downloads"));
        icon.setImageResource(DownloadTrackPatch.arsoundIcon("ic_actions_download_initial"));

        row.setBackgroundResource(DownloadTrackPatch.selectableBackground(context));
        row.setOnClickListener(v -> {
            // The activity context is needed for the result dialog.
            Context activityContext = dialog.getOwnerActivity() != null ? dialog.getOwnerActivity() : context;
            dialog.dismiss();
            checkPlaylist(activityContext, playlistId);
        });

        menuItems.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ViewGroup deleteRow = DownloadTrackPatch.createMenuRow(context,
                text("Удалить скачанные треки", "Delete downloaded tracks"), "ic_actions_delete", v -> {
                    Context activityContext = dialog.getOwnerActivity() != null ? dialog.getOwnerActivity() : context;
                    dialog.dismiss();
                    confirmDelete(activityContext, playlistId);
                });
        deleteRow.setTag(DELETE_ROW_TAG);
        menuItems.addView(deleteRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * Asks before removing the files of the playlist tracks Arsound downloaded.
     * Music imported from the phone is not a download and is never touched here.
     */
    private static void confirmDelete(Context context, String playlistId) {
        DownloadTrackPatch.showToast(context, text("Считаю скачанные треки…", "Counting downloaded tracks…"));

        Utils.runOnBackgroundThread(() -> {
            try {
                List<String> downloaded = downloadedTracksOf(playlistId);
                if (downloaded == null) {
                    DownloadTrackPatch.showToast(context, text("Не удалось получить треки, ошибка " + lastError,
                            "Could not load the tracks, error " + lastError));
                    return;
                }
                if (downloaded.isEmpty()) {
                    // A playlist whose downloads all failed or were deleted elsewhere leaves the Downloads screen.
                    setPlaylistDownloaded(playlistId, false);
                    DownloadTrackPatch.showToast(context,
                            text("В этом плейлисте нет скачанных треков", "No downloaded tracks in this playlist"));
                    return;
                }

                Utils.runOnMainThread(() -> new app.revanced.extension.soundcloud.shared.ArsoundDialog(context)
                        .setTitle(text("Удалить скачанные треки?", "Delete downloaded tracks?"))
                        .setMessage(text("Будут удалены файлы " + downloaded.size() + " треков из папки " + DownloadIndex.placeOf(downloaded) + ". "
                                        + "Импортированные с телефона треки останутся.",
                                "The files of " + downloaded.size() + " tracks will be removed from " + DownloadIndex.placeOf(downloaded) + ". "
                                        + "Music imported from the phone stays."))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(text("Удалить " + downloaded.size(), "Delete " + downloaded.size()),
                                (d, which) -> deleteAll(context.getApplicationContext(), playlistId, downloaded))
                        .show());
            } catch (Exception ex) {
                Logger.printException(() -> "Could not count downloaded tracks", ex);
                DownloadTrackPatch.showToast(context,
                        text("Не удалось посчитать скачанные треки", "Could not count the downloaded tracks"));
            }
        });
    }

    /** The tracks of the playlist that Arsound downloaded, or null if the server did not answer. */
    private static List<String> downloadedTracksOf(String playlistId) throws Exception {
        List<String> ids = trackIdsOf(playlistId, null);
        if (ids == null) return null;
        List<String> downloaded = new ArrayList<>();
        for (String id : ids) {
            // A track still being assembled has no record yet, but would get one once packed.
            if (DownloadTrackPatch.isDownloaded(id) || DownloadProgress.isAssembling(id)) downloaded.add(id);
        }
        return downloaded;
    }

    /** The ids of all liked tracks, newest first, from the server; null if it did not answer. */
    private static List<String> likedTrackIds() throws Exception {
        String[] me = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/me");
        if (me[1] == null) {
            lastError = me[0];
            return null;
        }
        long userId = new JSONObject(me[1]).getLong("id");
        List<String> ids = new ArrayList<>();
        String url = "https://api-v2.soundcloud.com/users/" + userId + "/track_likes?limit=200&linked_partitioning=1";
        while (url != null) {
            String[] page = DownloadTrackPatch.apiGet(url);
            if (page[1] == null) {
                if (!ids.isEmpty()) break;
                lastError = page[0];
                return null;
            }
            JSONObject json = new JSONObject(page[1]);
            JSONArray likes = json.optJSONArray("collection");
            if (likes == null || likes.length() == 0) break;
            for (int i = 0; i < likes.length(); i++) {
                JSONObject track = likes.getJSONObject(i).optJSONObject("track");
                if (track != null && track.has("id")) ids.add(String.valueOf(track.getLong("id")));
            }
            url = json.isNull("next_href") ? null : json.optString("next_href", null);
        }
        int count = ids.size();
        Logger.printInfo(() -> "Liked tracks for the check: " + count);
        return ids;
    }

    /**
     * The SoundCloud tracks of the playlist: those on the server, then those added on this phone.
     *
     * @param imported Receives the number of imported files added on this phone, may be null.
     * @return The track ids, or null if the server did not answer.
     */
    private static List<String> trackIdsOf(String playlistId, int[] imported) throws Exception {
        String[] playlist = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/playlists/" + playlistId);
        if (playlist[1] == null) {
            lastError = playlist[0];
            return null;
        }
        List<String> ids = new ArrayList<>();
        JSONArray tracks = new JSONObject(playlist[1]).getJSONArray("tracks");
        for (int i = 0; i < tracks.length(); i++) ids.add(String.valueOf(tracks.getJSONObject(i).getLong("id")));

        // Tracks added on the phone are not on the server: without them a playlist made of them looks empty.
        for (String entry : app.revanced.extension.soundcloud.local.LocalAdditions.getShownEntries("soundcloud:playlists:" + playlistId)) {
            if (entry.startsWith("file:")) {
                if (imported != null) imported[0]++;
                continue;
            }
            String id = DownloadTrackPatch.parseTrackId(entry);
            if (id != null && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    private static volatile String lastError;

    private static void deleteAll(Context context, String playlistId, List<String> trackIds) {
        setPlaylistDownloaded(playlistId, false);
        Utils.runOnBackgroundThread(() -> {
            int deleted = 0;
            for (String trackId : trackIds) {
                if (DownloadTrackPatch.deleteDownload(context, trackId)) deleted++;
            }
            int count = deleted;
            int failed = trackIds.size() - deleted;
            DownloadTrackPatch.showToast(context, failed == 0
                    ? text("Удалено треков: " + count, "Deleted " + count + " tracks")
                    : text("Удалено: " + count + ", не удалось: " + failed,
                    "Deleted " + count + ", could not delete " + failed));
            DownloadProgress.onDownloadsDeleted();
        });
    }

    static void checkPlaylist(Context context, String playlistId) {
        int[] imported = {0};
        check(context, playlistId, () -> trackIdsOf(playlistId, imported), imported);
    }

    /** Checks the liked tracks and offers to download the available ones, like a playlist. */
    static void checkLikes(Context context) {
        check(context, null, DownloadPlaylistPatch::likedTrackIds, new int[]{0});
    }

    private interface TrackIds {
        /** @return The track ids, or null if the server did not answer. */
        List<String> get() throws Exception;
    }

    /**
     * @param playlistId The playlist, or null for the likes.
     * @param imported   Receives the number of imported files the list has.
     */
    private static void check(Context context, String playlistId, TrackIds trackIds, int[] imported) {
        // One check at a time: a second tap started a second full check of the same tracks, and the two shared the
        // one progress counter at the bottom.
        if (!checking.compareAndSet(false, true)) {
            DownloadTrackPatch.showToast(context, text("Проверка уже идёт", "A check is already running"));
            return;
        }
        // The progress counter at the bottom shows the check, a toast would cover it.
        ProgressPill.onCheckLoading();

        Utils.runOnBackgroundThread(() -> {
            try {
                checkNow(context, playlistId, trackIds, imported);
            } finally {
                checking.set(false);
            }
        });
    }

    /** Set while a check runs. */
    private static final java.util.concurrent.atomic.AtomicBoolean checking = new java.util.concurrent.atomic.AtomicBoolean();

    private static void checkNow(Context context, String playlistId, TrackIds trackIds, int[] imported) {
        try {
            List<String> ids = trackIds.get();
            if (ids == null) {
                ProgressPill.onCheckFinished();
                DownloadTrackPatch.showToast(context, text("Не удалось получить треки, ошибка " + lastError,
                        "Could not load the tracks, error " + lastError));
                return;
            }

            // The playlist only contains the ids of most tracks, so the track details are requested separately.

            List<TrackInfo> downloadable = new ArrayList<>();
            List<String> unavailableTitles = new ArrayList<>();
            // DRM tracks Arsound cannot save as files; they go to SoundCloud's own offline download.
            List<String> drmIds = new ArrayList<>();
            int[] alreadyDownloaded = {0, 0}; // downloaded, still downloading
            // Without the listing of the files every track would count as not downloaded, as the paths of the
            // files are closed to the app since Android 10.
            DownloadIndex.awaitListing();
            ProgressPill.onCheckStarted(ids.size());

            // Tracks on the phone are counted at once; the rest wait for their file links below.
            List<JSONObject> toResolve = new ArrayList<>();
            int[] checked = {0};
            // Whether a Go+ track [0] or a track blocked in the region [1] is among the unavailable ones; guarded by checked.
            boolean[] restricted = {false, false};
            // Tracks of requests the server did not answer: unknown, not "unavailable".
            int[] unchecked = {0};
            for (int start = 0; start < ids.size(); start += TRACKS_PER_REQUEST) {
                List<String> chunk = ids.subList(start, Math.min(ids.size(), start + TRACKS_PER_REQUEST));
                String chunkUrl = "https://api-v2.soundcloud.com/tracks?ids=" + String.join(",", chunk);
                String[] tracks = DownloadTrackPatch.apiGet(chunkUrl);
                // One more try for a server error: a whole chunk of 50 tracks is worth it.
                if (tracks[1] == null && tracks[0].startsWith("5")) {
                    Thread.sleep(2_000);
                    tracks = DownloadTrackPatch.apiGet(chunkUrl);
                }
                if (tracks[1] == null) {
                    lastError = tracks[0];
                    unchecked[0] += chunk.size();
                }
                int answered = 0;
                if (tracks[1] != null) {
                    JSONArray array = new JSONArray(tracks[1]);
                    answered = array.length();
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject track = array.getJSONObject(i);
                        // Metadata flags can be stale or overly broad. Resolve the actual stream for
                        // every track and let the authorized stream endpoint decide availability.
                        if (!track.optBoolean("streamable", true)) {
                            checked[0]++;
                            continue;
                        }
                        String id = String.valueOf(track.getLong("id"));
                        switch (DownloadTrackPatch.getDownloadState(context, id)) {
                            case DOWNLOADED:
                                alreadyDownloaded[0]++;
                                checked[0]++;
                                break;
                            case IN_PROGRESS:
                                alreadyDownloaded[1]++;
                                checked[0]++;
                                break;
                            default:
                                toResolve.add(track);
                        }
                    }
                }
                // Tracks missing from the answer (deleted on the server) count as checked.
                if (tracks[1] != null) checked[0] += chunk.size() - answered;
                else checked[0] += chunk.size();
                ProgressPill.onChecked(checked[0]);
            }

            // The track data does not show region blocks: only the file link does. The check asks for
            // it now, so "can be downloaded" means the download will really start. One link can take
            // many requests, so a few tracks are asked at once.
            TrackInfo[] found = new TrackInfo[toResolve.size()];
            String[] unavailable = new String[toResolve.size()];
            String[] drm = new String[toResolve.size()];
            // Each track's data is let go once its link is resolved: a long list kept all of it to the end.
            JSONObject[] pending = toResolve.toArray(new JSONObject[0]);
            toResolve.clear();
            // A network that drops (a VPN that restarts, a DNS that does not answer) fails many tracks at once, and says
            // nothing about them: they are asked again after a pause, a few at a time, before they count as unchecked.
            List<Integer> todo = new ArrayList<>();
            for (int i = 0; i < pending.length; i++) todo.add(i);
            for (int round = 0; round <= RETRY_PAUSES_MS.length && !todo.isEmpty(); round++) {
                boolean last = round == RETRY_PAUSES_MS.length;
                if (round > 0) {
                    int waiting = todo.size();
                    long pause = RETRY_PAUSES_MS[round - 1];
                    Logger.printInfo(() -> "Check: " + waiting + " tracks without an answer, asked again in " + pause / 1000 + " s");
                    Thread.sleep(pause);
                }
                boolean[] failedNow = new boolean[pending.length];
                int threads = round == 0 ? PARALLEL_CHECKS : RETRY_PARALLEL_CHECKS;
                ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, todo.size())));
                try {
                    for (int index : todo) {
                        pool.execute(() -> {
                            JSONObject track = pending[index];
                            String id = String.valueOf(track.optLong("id"));
                            String title = track.optString("title");
                            TrackSource source = UnavailableTracks.get(id);
                            boolean failed = false;
                            if (source == null) {
                                try {
                                    source = DownloadTrackPatch.resolveSource(id, track);
                                    if (!source.isDownloadable()) UnavailableTracks.put(id, source.status);
                                } catch (Exception ex) {
                                    // A network or server failure says nothing about the track: it is not remembered.
                                    failed = true;
                                    Logger.printInfo(() -> "No file link for " + id + ": " + ex);
                                }
                            }
                            failedNow[index] = failed;
                            if (!failed) pending[index] = null;
                            if (source != null && source.isDownloadable()) {
                                found[index] = new TrackInfo(id, title, source);
                            } else if (source != null && DownloadTrackPatch.goesToNativeOffline(source.status)) {
                                drm[index] = id;
                            } else if (!failed) {
                                unavailable[index] = title + (source == null ? "" : " (" + source.reason() + ")");
                                if (source != null && RestrictionNote.isRestriction(source.status)) {
                                    synchronized (checked) {
                                        if (source.status == TrackSource.Status.REGION_BLOCKED) restricted[1] = true;
                                        else restricted[0] = true;
                                    }
                                }
                            }
                            synchronized (checked) {
                                // A track that failed is counted once its last try is made.
                                if (failed && last) unchecked[0]++;
                                if (!failed || last) ProgressPill.onChecked(++checked[0]);
                            }
                        });
                    }
                    pool.shutdown();
                    if (!pool.awaitTermination(1, TimeUnit.HOURS)) Logger.printInfo(() -> "Playlist check timed out");
                } finally {
                    // Stops the checks still waiting (also their pauses for the rate limit) if this one was cut short.
                    pool.shutdownNow();
                }
                List<Integer> again = new ArrayList<>();
                for (int index : todo) if (failedNow[index]) again.add(index);
                todo = again;
            }
            synchronized (checked) {
                for (int i = 0; i < found.length; i++) {
                    if (found[i] != null) downloadable.add(found[i]);
                    else if (drm[i] != null) drmIds.add(drm[i]);
                    else if (unavailable[i] != null) unavailableTitles.add(unavailable[i]);
                }
            }
            ProgressPill.onCheckFinished();

            int notChecked = unchecked[0];
            Logger.printInfo(() -> "Playlist " + playlistId + ": " + ids.size() + " tracks, imported " + imported[0]
                    + ", downloaded " + alreadyDownloaded[0] + ", downloading " + alreadyDownloaded[1] + ", can download "
                    + downloadable.size() + ", DRM " + drmIds.size() + ", not checked " + notChecked);
            String footnote = RestrictionNote.footnote(restricted[0], restricted[1]);
            Utils.runOnMainThread(() -> showResult(context, playlistId, ids.size(), imported[0], alreadyDownloaded[0], alreadyDownloaded[1],
                    notChecked, downloadable, unavailableTitles, drmIds, footnote));
        } catch (Exception ex) {
            Logger.printException(() -> "Playlist check failure", ex);
            ProgressPill.onCheckFinished();
            DownloadTrackPatch.showToast(context, text("Не удалось проверить треки", "Could not check the tracks"));
        }
    }

    /**
     * The screen to show the result on: the one on top now, not the one the check was started from, which may have been
     * made again (a turn) or closed meanwhile. Null when no screen is on top.
     */
    private static Context resultScreen(Context started) {
        android.app.Activity resumed = app.revanced.extension.soundcloud.update.UpdateChecker.resumedActivity();
        if (resumed != null && !resumed.isFinishing() && !resumed.isDestroyed()) return resumed;
        if (started instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) started;
            if (!activity.isFinishing() && !activity.isDestroyed()) return activity;
        }
        return null;
    }

    /**
     * @param notChecked Tracks the server gave no answer about (network, rate limit or server error).
     * @param footnote   The note about Go+ and region-blocked tracks among the unavailable ones, or empty.
     */
    private static void showResult(Context started, String playlistId, int total, int imported, int downloaded, int downloading,
                                   int notChecked, List<TrackInfo> downloadable, List<String> unavailableTitles, List<String> drmIds,
                                   String footnote) {
        Context context = resultScreen(started);
        if (context == null) {
            // No screen to ask on: the result is told, and the download button can be pressed again.
            DownloadTrackPatch.showToast(started.getApplicationContext(), text("Проверка готова: можно скачать "
                    + downloadable.size() + ". Нажмите кнопку скачивания ещё раз, чтобы выбрать.", "The check is done: "
                    + downloadable.size() + " can be downloaded. Press the download button again to choose."));
            return;
        }
        int unavailable = total - downloaded - downloading - downloadable.size() - drmIds.size() - notChecked;
        StringBuilder summary = new StringBuilder();
        summary.append(playlistId == null
                ? text("Лайкнутых треков: ", "Liked tracks: ")
                : text("Треков в плейлисте: ", "Tracks in the playlist: ")).append(total + imported).append('\n');
        if (imported > 0) summary.append(text("Импортированы с телефона: ", "Imported from the phone: ")).append(imported).append('\n');
        // Worded so that "can be downloaded" is not read as "was downloaded": these numbers are counted before anything starts.
        summary.append(text("Уже скачаны (лежат на телефоне или на карте): ", "Already downloaded (on the phone or a card): "))
                .append(downloaded).append('\n');
        if (downloading > 0) summary.append(text("Скачиваются сейчас: ", "Downloading now: ")).append(downloading).append('\n');
        summary.append(text("Ещё не скачаны, но доступны для скачивания: ", "Not downloaded yet, but available to download: "))
                .append(downloadable.size()).append('\n');
        if (!drmIds.isEmpty()) {
            summary.append(text("Защищены DRM (сохраню в офлайн-библиотеку SoundCloud, играют только в приложении): ",
                    "DRM protected (saved to SoundCloud's offline library, play in the app only): ")).append(drmIds.size()).append('\n');
        }
        if (notChecked > 0) {
            summary.append(text("Не удалось проверить (нет ответа сервера, попробуйте позже): ",
                    "Could not be checked (no answer from the server, try again later): ")).append(notChecked).append('\n');
        }
        if (unavailable > 0) {
            summary.append(text("Недоступно (заблокировано в регионе, по подписке, отрывки или закрытые): ",
                    "Not available (blocked in your region, subscription, previews or private): ")).append(unavailable).append('\n');
            for (String title : unavailableTitles) summary.append("  ✕ ").append(title).append('\n');
            if (!footnote.isEmpty()) summary.append(footnote).append('\n');
        }

        // Tracks that this tap would start: the ones Arsound saves as files plus the DRM ones handed to SoundCloud.
        int toStart = downloadable.size() + drmIds.size();
        app.revanced.extension.soundcloud.shared.ArsoundDialog builder = new app.revanced.extension.soundcloud.shared.ArsoundDialog(context);
        // Everything available is on the phone: the playlist counts as downloaded.
        if (playlistId != null && toStart == 0 && downloading == 0 && downloaded > 0 && notChecked == 0) {
            setPlaylistDownloaded(playlistId, true);
        }
        if (toStart == 0) {
            builder.setTitle(notChecked > 0
                            ? text("Не все треки удалось проверить", "Not every track could be checked")
                            : downloaded + downloading + imported > 0
                            ? text("Всё доступное уже скачано", "Everything available is downloaded")
                            : text("Нечего скачать", "Nothing to download"))
                    .setMessage(summary.toString().trim())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        if (!downloadable.isEmpty()) {
            summary.append('\n').append(text("Будут скачаны:", "Will be downloaded:")).append('\n');
            for (TrackInfo track : downloadable) {
                summary.append("• ").append(track.title);
                // Such a track is assembled from its stream by the app, which takes a few seconds of CPU time.
                if (track.needsAssembly()) summary.append(text(" (фоновая обработка)", " (background processing)"));
                summary.append('\n');
            }
        }

        // A question, so it is plain that nothing has been downloaded yet.
        builder.setTitle(downloaded > 0
                        ? text("Скачать ещё " + toStart + "?", "Download " + toStart + " more?")
                        : text("Скачать " + toStart + "?", "Download " + toStart + "?"))
                .setMessage(summary.toString().trim())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(text("Скачать " + toStart, "Download " + toStart),
                        (d, which) -> {
                            if (!downloadable.isEmpty()) downloadAll(context, playlistId, downloadable);
                            // DRM tracks go to SoundCloud's own offline download, queued as one batch so the native
                            // service starts once instead of spamming a notification per track.
                            if (!drmIds.isEmpty()) {
                                app.revanced.extension.soundcloud.offline.NativeOffline.makeAvailableAll(context, drmIds);
                            }
                        })
                .show();
    }

    private static void downloadAll(Context context, String playlistId, List<TrackInfo> tracks) {
        Context appContext = context.getApplicationContext();
        List<String> ids = new ArrayList<>();
        for (TrackInfo track : tracks) ids.add(track.id);
        ProgressPill.onDownloadsQueued(ids);
        Utils.runOnBackgroundThread(() -> {
            int started = 0;
            for (TrackInfo track : tracks) {
                boolean ok = false;
                try {
                    // Checked again: the dialog may have stayed open while the same track was downloaded elsewhere.
                    if (DownloadTrackPatch.getDownloadState(appContext, track.id)
                            != DownloadTrackPatch.DownloadState.NOT_DOWNLOADED) {
                        ok = true;
                        started++;
                        if (playlistId != null) setPlaylistDownloaded(playlistId, true);
                        continue;
                    }
                    ok = DownloadTrackPatch.downloadSilently(appContext, track.id, track.source, playlistId);
                    if (ok) started++;
                } catch (Exception ex) {
                    // One summary toast below covers the tracks that did not start, instead of a toast for each.
                    Logger.printInfo(() -> "Download failure for track " + track.id, ex);
                } finally {
                    ProgressPill.onDownloadStarted(track.id, ok);
                }
                // Listed on the Downloads screen once something of it really is on the way.
                if (ok && playlistId != null) setPlaylistDownloaded(playlistId, true);
            }

            int count = started;
            int failed = tracks.size() - started;
            // The progress counter shows the downloads, a toast is left only for tracks that did not start.
            if (failed > 0) DownloadTrackPatch.showToast(appContext, text(
                    "Скачивание началось: " + count + ", не удалось начать: " + failed,
                    "Downloading " + count + " tracks, could not start " + failed));
        });
    }

}
