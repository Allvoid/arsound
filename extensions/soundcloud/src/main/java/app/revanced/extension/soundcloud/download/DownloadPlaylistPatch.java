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
 * It finds the tracks whose artist enabled free downloads and offers to download all of them.
 */
@SuppressWarnings("unused")
public final class DownloadPlaylistPatch {
    private static final String ROW_TAG = "arsound_playlist_download_row";
    private static final Pattern PLAYLIST_ID = Pattern.compile("^soundcloud:playlists:(\\d+)$");
    private static final int TRACKS_PER_REQUEST = 50;

    private static final class TrackInfo {
        final String id;
        final String title;

        TrackInfo(String id, String title) {
            this.id = id;
            this.title = title;
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
            dialog.getWindow().getDecorView().post(() -> addRow(dialog, playlistId));
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
        icon.setImageResource(Utils.getResourceIdentifier(ResourceType.DRAWABLE, "ic_actions_download_initial"));

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
                for (int start = 0; start < ids.size(); start += TRACKS_PER_REQUEST) {
                    List<String> chunk = ids.subList(start, Math.min(ids.size(), start + TRACKS_PER_REQUEST));
                    String[] tracks = DownloadTrackPatch.apiGet(
                            "https://api-v2.soundcloud.com/tracks?ids=" + String.join(",", chunk));
                    if (tracks[1] == null) continue;

                    JSONArray array = new JSONArray(tracks[1]);
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject track = array.getJSONObject(i);
                        if (track.optBoolean("downloadable") && track.optBoolean("has_downloads_left", true)) {
                            downloadable.add(new TrackInfo(String.valueOf(track.getLong("id")), track.optString("title")));
                        }
                    }
                }

                Logger.printInfo(() -> "Playlist " + playlistId + ": " + downloadable.size()
                        + " of " + ids.size() + " tracks downloadable");
                Utils.runOnMainThread(() -> showResult(context, ids.size(), downloadable));
            } catch (Exception ex) {
                Logger.printException(() -> "Playlist check failure", ex);
                DownloadTrackPatch.showToast(context, text("Не удалось проверить треки", "Could not check the tracks"));
            }
        });
    }

    private static void showResult(Context context, int total, List<TrackInfo> downloadable) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        if (downloadable.isEmpty()) {
            builder.setTitle(text("Нечего скачать", "Nothing to download"))
                    .setMessage(text("Ни один из " + total + " треков автор не разрешил скачивать.",
                            "None of the " + total + " tracks can be downloaded."))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        StringBuilder titles = new StringBuilder();
        for (TrackInfo track : downloadable) titles.append("• ").append(track.title).append('\n');

        builder.setTitle(text("Можно скачать " + downloadable.size() + " из " + total,
                        downloadable.size() + " of " + total + " tracks can be downloaded"))
                .setMessage(titles.toString().trim())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(text("Скачать все", "Download all"), (d, which) -> downloadAll(context, downloadable))
                .show();
    }

    private static void downloadAll(Context context, List<TrackInfo> tracks) {
        Context appContext = context.getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            int started = 0;
            for (TrackInfo track : tracks) {
                try {
                    if (DownloadTrackPatch.downloadSilently(appContext, track.id)) started++;
                } catch (Exception ex) {
                    Logger.printException(() -> "Download failure for track " + track.id, ex);
                }
            }

            int count = started;
            DownloadTrackPatch.showToast(appContext, text("Скачивание началось: " + count + " в Музыка/Arsound",
                    "Downloading " + count + " tracks to Music/Arsound"));
        });
    }
}
