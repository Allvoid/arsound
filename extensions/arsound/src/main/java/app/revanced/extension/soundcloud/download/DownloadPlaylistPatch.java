package app.revanced.extension.soundcloud.download;

import static app.revanced.extension.soundcloud.download.DownloadTrackPatch.text;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;

/**
 * Adds a "Check downloads" row to the playlist and album menu.
 * It finds playable, non-subscription tracks and offers to download them all. Each task prefers
 * the author-provided file, then falls back to the official progressive stream.
 */
@SuppressWarnings("unused")
public final class DownloadPlaylistPatch {
    private static final String ROW_TAG = "arsound_playlist_download_row";
    private static final Pattern PLAYLIST_ID = Pattern.compile("^soundcloud:playlists:(\\d+)$");
    private static final int TRACKS_PER_REQUEST = 50;

    private static final class TrackInfo {
        final String id;
        final String title;
        /** The file URL found by the check. */
        final String url;
        final long resolvedAt = System.currentTimeMillis();

        TrackInfo(String id, String title, String url) {
            this.id = id;
            this.title = title;
            this.url = url;
        }

        /** Stream links expire, so a link from a dialog left open for long is resolved again. */
        String freshUrl() {
            return System.currentTimeMillis() - resolvedAt < 5 * 60_000 ? url : null;
        }
    }

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
                                String url = null;
                                try {
                                    url = DownloadTrackPatch.resolveDownloadUrl(id);
                                } catch (Exception ex) {
                                    Logger.printInfo(() -> "No file link for " + id + ": " + ex);
                                }
                                if (url != null) downloadable.add(new TrackInfo(id, track.optString("title"), url));
                                else unavailableTitles.add(track.optString("title"));
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
        for (TrackInfo track : downloadable) summary.append("• ").append(track.title).append('\n');

        builder.setTitle(text("Можно скачать ещё " + downloadable.size(), downloadable.size() + " more can be downloaded"))
                .setMessage(summary.toString().trim())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(text("Скачать " + downloadable.size(), "Download " + downloadable.size()),
                        (d, which) -> downloadAll(context, playlistId, downloadable))
                .show();
    }

    private static void downloadAll(Context context, String playlistId, List<TrackInfo> tracks) {
        Context appContext = context.getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            int started = 0;
            for (TrackInfo track : tracks) {
                try {
                    // Checked again: the dialog may have stayed open while the same track was downloaded elsewhere.
                    if (DownloadTrackPatch.getDownloadState(appContext, track.id)
                            != DownloadTrackPatch.DownloadState.NOT_DOWNLOADED) continue;
                    if (DownloadTrackPatch.downloadSilently(appContext, track.id, track.freshUrl(), playlistId)) started++;
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
