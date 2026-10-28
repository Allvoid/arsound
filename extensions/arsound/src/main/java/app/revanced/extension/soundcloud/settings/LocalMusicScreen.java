package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.local.LocalAdditions;
import app.revanced.extension.soundcloud.local.LocalMusic;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/** "My files": the audio files imported from the phone, with the button to import more. */
final class LocalMusicScreen extends SettingsScreen {
    static final String ID = "local_music";

    private static final int REQUEST_IMPORT = 1;
    /** Rows added to the list per frame, so a library of thousands of files does not freeze the screen. */
    private static final int LOCAL_ROWS_PER_STEP = 30;

    private LinearLayout localTrackList;
    private List<LocalMusic.Track> localTracks = new ArrayList<>();
    /** Bumped by every reload, so an older read that finishes late cannot replace a newer list. */
    private int localLoadGeneration;
    /** Bumped by every redraw of the list, so the rows of an older list stop being added. */
    private int localRenderGeneration;

    LocalMusicScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    View create() {
        LinearLayout root = ui.createRoot();
        root.addView(ui.createToolbar());

        ScrollView scrollView = new ScrollView(activity);
        LinearLayout list = ui.createColumn();
        scrollView.addView(list);
        root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView title = ui.createText("H1.Primary", text("Мои файлы", "My files"));
        title.setPadding(ui.dimen("spacing_m"), ui.dimen("spacing_s"), ui.dimen("spacing_m"), ui.dimen("spacing_l"));
        list.addView(title);

        list.addView(ui.createActionRow(
                text("Импортировать файлы", "Import files"),
                text("MP3, M4A, FLAC, OGG, WAV. Файлы копируются в память приложения.",
                        "MP3, M4A, FLAC, OGG, WAV. Files are copied into the app storage."),
                v -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("audio/*")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    try {
                        activity.startActivityForResult(intent, REQUEST_IMPORT);
                    } catch (ActivityNotFoundException | SecurityException ex) {
                        // Some firmware has no system document picker.
                        Logger.printException(() -> "Could not open the file picker", ex);
                        Toast.makeText(activity, text("Не найдено приложение для выбора файлов",
                                "No app to pick files was found"), Toast.LENGTH_LONG).show();
                    }
                }
        ));
        list.addView(ui.createSubHeading(text("Треки", "Tracks")));
        localTrackList = ui.createColumn();
        list.addView(localTrackList);
        reloadLocalTracks();

        return root;
    }

    private void reloadLocalTracks() {
        int generation = ++localLoadGeneration;
        Utils.runOnBackgroundThread(() -> {
            List<LocalMusic.Track> tracks = LocalMusic.getTracks(activity);
            Utils.runOnMainThread(() -> {
                if (generation != localLoadGeneration || activity.isFinishing() || activity.isDestroyed()) return;
                showLocalTracks(tracks);
            });
        });
    }

    private void showLocalTracks(List<LocalMusic.Track> tracks) {
        localTracks = tracks;
        int generation = ++localRenderGeneration;
        localTrackList.removeAllViews();
        if (tracks.isEmpty()) {
            TextView empty = ui.createText("Body.Secondary", text("Пока пусто. Нажмите «Импортировать файлы».",
                    "Nothing here yet. Tap \"Import files\"."));
            empty.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), ui.dimen("spacing_s"));
            localTrackList.addView(empty);
            return;
        }

        addLocalTrackRows(tracks, 0, generation);
    }

    /** Adds the next few rows and schedules the rest, so no single frame builds thousands of views. */
    private void addLocalTrackRows(List<LocalMusic.Track> tracks, int from, int generation) {
        if (generation != localRenderGeneration || activity.isFinishing() || activity.isDestroyed()) return;

        int to = Math.min(from + LOCAL_ROWS_PER_STEP, tracks.size());
        for (int i = from; i < to; i++) {
            LocalMusic.Track track = tracks.get(i);
            int index = i;
            long seconds = track.durationMs / 1000;
            String details = (track.artist.isEmpty() ? "" : track.artist + " · ")
                    + String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
            ActionRow row = ui.createActionRow(track.title, details, v -> playLocal(index, false));
            row.setOnLongClickListener(v -> {
                new ArsoundDialog(activity)
                        .setTitle(track.title)
                        .setNeutralButton(text("В плейлист…", "To playlist…"), (dialog, which) ->
                                LocalAdditions.pickPlaylist(activity, LocalAdditions.fileEntry(track.file)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(text("Удалить файл", "Delete file"), (dialog, which) ->
                                // Deleting also writes the cover prefs and scans the covers folder.
                                Utils.runOnBackgroundThread(() -> {
                                    try {
                                        LocalMusic.delete(track);
                                    } catch (Exception ex) {
                                        Logger.printException(() -> "Could not delete " + track.file, ex);
                                    }
                                    Utils.runOnMainThread(this::reloadLocalTracks);
                                }))
                        .show();
                return true;
            });
            localTrackList.addView(row);
        }

        if (to < tracks.size()) localTrackList.post(() -> addLocalTrackRows(tracks, to, generation));
    }

    private void playLocal(int index, boolean shuffle) {
        List<File> files = new ArrayList<>();
        for (LocalMusic.Track track : localTracks) files.add(track.file);
        if (files.isEmpty()) return;

        if (LocalMusic.play(files, index, shuffle)) {
            activity.finish();
        } else {
            Toast.makeText(activity, text("Плеер ещё не готов. Откройте SoundCloud и попробуйте снова.",
                    "The player is not ready. Open SoundCloud and try again."), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_IMPORT || resultCode != Activity.RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        Toast.makeText(activity, text("Импортирую…", "Importing…"), Toast.LENGTH_SHORT).show();
        Utils.runOnBackgroundThread(() -> {
            int count = LocalMusic.importFiles(activity, uris);
            Utils.runOnMainThread(() -> {
                Toast.makeText(activity, text("Импортировано: " + count, "Imported: " + count), Toast.LENGTH_SHORT).show();
                reloadLocalTracks();
            });
        });
    }
}
