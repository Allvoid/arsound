package app.revanced.extension.soundcloud.download;

import static app.revanced.extension.soundcloud.download.DownloadTrackPatch.text;

import android.app.AlertDialog;
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
                addRow(dialog, playlistId);
                app.revanced.extension.soundcloud.local.LocalAdditions.addPlaylistMenuRow(dialog, "soundcloud:playlists:" + playlistId);
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
                if (downloaded.isEmpty()) {
                    DownloadTrackPatch.showToast(context,
                            text("В этом плейлисте нет скачанных треков", "No downloaded tracks in this playlist"));
                    return;
                }

                Utils.runOnMainThread(() -> new AlertDialog.Builder(context)
                        .setTitle(text("Удалить скачанные треки?", "Delete downloaded tracks?"))
                        .setMessage(text("Будут удалены файлы " + downloaded.size() + " треков из папки Музыка/Arsound. "
                                        + "Импортированные с телефона треки останутся.",
                                "The files of " + downloaded.size() + " tracks will be removed from Music/Arsound. "
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

    /** The tracks of the playlist that Arsound downloaded. */
    private static List<String> downloadedTracksOf(String playlistId) throws Exception {
        List<String> downloaded = new ArrayList<>();
        String[] playlist = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/playlists/" + playlistId);
        if (playlist[1] == null) return downloaded;

        JSONArray tracks = new JSONObject(playlist[1]).getJSONArray("tracks");
        for (int i = 0; i < tracks.length(); i++) {
            String id = String.valueOf(tracks.getJSONObject(i).getLong("id"));
            if (DownloadTrackPatch.isDownloaded(id)) downloaded.add(id);
        }
        return downloaded;
    }

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

    private static void checkPlaylist(Context context, String playlistId) {
        DownloadTrackPatch.showToast(context, text("Проверяю треки…", "Checking tracks…"));

        Utils.runOnBackgroundThread(() -> {
            try {
                String[] playlist = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/playlists/" + playlistId);
                if (playlist[1] == null) {
                    DownloadTrackPatch.showToast(context, text("Не удалось получить треки, ошибка " + playlist[0],
                            "Could not load the tracks, error " + playlist[0]));
                    return;
                }

                // The playlist only contains the ids of most tracks, so the track details are requested separately.
                JSONArray playlistTracks = new JSONObject(playlist[1]).getJSONArray("tracks");
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < playlistTracks.length(); i++) {
                    ids.add(String.valueOf(playlistTracks.getJSONObject(i).getLong("id")));
                }

                List<TrackInfo> downloadable = new ArrayList<>();
                List<String> unavailableTitles = new ArrayList<>();
                int[] alreadyDownloaded = {0, 0}; // downloaded, still downloading
                for (int start = 0; start < ids.size(); start += TRACKS_PER_REQUEST) {
                    List<String> chunk = ids.subList(start, Math.min(ids.size(), start + TRACKS_PER_REQUEST));
                    String[] tracks = DownloadTrackPatch.apiGet(
                            "https://api-v2.soundcloud.com/tracks?ids=" + String.join(",", chunk));
                    if (tracks[1] == null) continue;

                    JSONArray array = new JSONArray(tracks[1]);
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject track = array.getJSONObject(i);
                        if (!track.optBoolean("streamable", true) || !isNotRestricted(track)) continue;
                        String id = String.valueOf(track.getLong("id"));
                        switch (DownloadTrackPatch.getDownloadState(context, id)) {
                            case DOWNLOADED:
                                alreadyDownloaded[0]++;
                                break;
                            case IN_PROGRESS:
                                alreadyDownloaded[1]++;
                                break;
                            default:
                                // The track data does not show region blocks: only the file link does. The check
                                // asks for it now, so "can be downloaded" means the download will really start.
                                TrackSource source = null;
                                try {
                                    source = DownloadTrackPatch.resolveSource(id);
                                } catch (Exception ex) {
                                    Logger.printInfo(() -> "No file link for " + id + ": " + ex);
                                }
                                if (source != null && source.isDownloadable()) {
                                    downloadable.add(new TrackInfo(id, track.optString("title"), source));
                                } else {
                                    unavailableTitles.add(track.optString("title")
                                            + (source == null ? "" : " (" + source.reason() + ")"));
                                }
                        }
                    }
                }

                Logger.printInfo(() -> "Playlist " + playlistId + ": " + ids.size() + " tracks, downloaded "
                        + alreadyDownloaded[0] + ", downloading " + alreadyDownloaded[1] + ", can download " + downloadable.size());
                Utils.runOnMainThread(() -> showResult(context, playlistId, ids.size(), alreadyDownloaded[0], alreadyDownloaded[1],
                        downloadable, unavailableTitles));
            } catch (Exception ex) {
                Logger.printException(() -> "Playlist check failure", ex);
                DownloadTrackPatch.showToast(context, text("Не удалось проверить треки", "Could not check the tracks"));
            }
        });
    }

    private static void showResult(Context context, String playlistId, int total, int downloaded, int downloading, List<TrackInfo> downloadable,
                                   List<String> unavailableTitles) {
        int unavailable = total - downloaded - downloading - downloadable.size();
        StringBuilder summary = new StringBuilder();
        summary.append(text("Треков в плейлисте: ", "Tracks in the playlist: ")).append(total).append('\n')
                .append(text("Уже скачано: ", "Already downloaded: ")).append(downloaded).append('\n');
        if (downloading > 0) summary.append(text("Скачиваются сейчас: ", "Downloading now: ")).append(downloading).append('\n');
        summary.append(text("Можно скачать: ", "Can be downloaded: ")).append(downloadable.size()).append('\n');
        if (unavailable > 0) {
            summary.append(text("Недоступно (заблокировано в регионе, по подписке, отрывки или закрытые): ",
                    "Not available (blocked in your region, subscription, previews or private): ")).append(unavailable).append('\n');
            for (String title : unavailableTitles) summary.append("  ✕ ").append(title).append('\n');
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        // Everything available is on the phone: the playlist counts as downloaded.
        if (downloadable.isEmpty() && downloading == 0 && downloaded > 0) setPlaylistDownloaded(playlistId, true);
        if (downloadable.isEmpty()) {
            builder.setTitle(downloaded + downloading > 0
                            ? text("Всё доступное уже скачано", "Everything available is downloaded")
                            : text("Нечего скачать", "Nothing to download"))
                    .setMessage(summary.toString().trim())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        summary.append('\n').append(text("Будут скачаны:", "Will be downloaded:")).append('\n');
        for (TrackInfo track : downloadable) {
            summary.append("• ").append(track.title);
            // Such a track is assembled from its stream by the app, which takes a few seconds of CPU time.
            if (track.needsAssembly()) summary.append(text(" (фоновая обработка)", " (background processing)"));
            summary.append('\n');
        }

        builder.setTitle(text("Можно скачать ещё " + downloadable.size(), downloadable.size() + " more can be downloaded"))
                .setMessage(summary.toString().trim())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(text("Скачать " + downloadable.size(), "Download " + downloadable.size()),
                        (d, which) -> downloadAll(context, playlistId, downloadable))
                .show();
    }

    private static void downloadAll(Context context, String playlistId, List<TrackInfo> tracks) {
        Context appContext = context.getApplicationContext();
        setPlaylistDownloaded(playlistId, true);
        Utils.runOnBackgroundThread(() -> {
            int started = 0;
            for (TrackInfo track : tracks) {
                try {
                    // Checked again: the dialog may have stayed open while the same track was downloaded elsewhere.
                    if (DownloadTrackPatch.getDownloadState(appContext, track.id)
                            != DownloadTrackPatch.DownloadState.NOT_DOWNLOADED) continue;
                    if (DownloadTrackPatch.downloadSilently(appContext, track.id, track.source, playlistId)) started++;
                } catch (Exception ex) {
                    Logger.printException(() -> "Download failure for track " + track.id, ex);
                }
            }

            int count = started;
            int failed = tracks.size() - started;
            DownloadTrackPatch.showToast(appContext, failed == 0
                    ? text("Скачивание началось: " + count + " в Музыка/Arsound", "Downloading " + count + " tracks to Music/Arsound")
                    : text("Скачивание началось: " + count + ", не удалось начать: " + failed,
                    "Downloading " + count + " tracks, could not start " + failed));
        });
    }

    private static boolean isNotRestricted(JSONObject track) {
        String policy = track.optString("policy").toUpperCase(java.util.Locale.US);
        String monetization = track.optString("monetization_model").toUpperCase(java.util.Locale.US);
        return !policy.contains("SNIP") && !policy.contains("BLOCK") && !policy.contains("SUB")
                && !monetization.contains("SUB") && !monetization.contains("GO_PLUS");
    }
}
