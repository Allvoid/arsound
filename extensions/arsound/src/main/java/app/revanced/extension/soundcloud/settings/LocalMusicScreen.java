package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
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

    /** The tracks, in a list that makes rows only for what is on screen: a library may hold thousands of files. */
    private ListView trackList;
    private final TrackAdapter adapter = new TrackAdapter();
    private TextView emptyNote;
    private List<LocalMusic.Track> localTracks = new ArrayList<>();
    /** Bumped by every reload, so an older read that finishes late cannot replace a newer list. */
    private int localLoadGeneration;

    LocalMusicScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    View create() {
        LinearLayout root = ui.createRoot();
        root.addView(ui.createToolbar());

        trackList = new ListView(activity);
        trackList.setDivider(null);
        trackList.setSelector(new android.graphics.drawable.ColorDrawable(0));
        root.addView(trackList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // What stood above the tracks scrolls with them, as before.
        LinearLayout header = ui.createColumn();
        TextView title = ui.createText("H1.Primary", text("Мои файлы", "My files"));
        title.setPadding(ui.dimen("spacing_m"), ui.dimen("spacing_s"), ui.dimen("spacing_m"), ui.dimen("spacing_l"));
        header.addView(title);

        header.addView(ui.createActionRow(
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
        header.addView(ui.createSubHeading(text("Треки", "Tracks")));
        emptyNote = ui.createText("Body.Secondary", text("Пока пусто. Нажмите «Импортировать файлы».",
                "Nothing here yet. Tap \"Import files\"."));
        emptyNote.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), ui.dimen("spacing_s"));
        emptyNote.setVisibility(View.GONE);
        header.addView(emptyNote);
        trackList.addHeaderView(header, null, false);
        trackList.setAdapter(adapter);
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
        emptyNote.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    /** The rows of the tracks; a row that scrolled away is filled with another track. */
    private final class TrackAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return localTracks.size();
        }

        @Override
        public Object getItem(int position) {
            return localTracks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ActionRow row = convertView instanceof ActionRow ? (ActionRow) convertView : ui.createActionRow("", "", null);
            LocalMusic.Track track = localTracks.get(position);
            long seconds = track.durationMs / 1000;
            row.title().setText(track.title);
            row.description().setText((track.artist.isEmpty() ? "" : track.artist + " · ")
                    + String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60));
            row.setOnClickListener(v -> playLocal(position, false));
            row.setOnLongClickListener(v -> {
                askAbout(track);
                return true;
            });
            return row;
        }
    }

    private void askAbout(LocalMusic.Track track) {
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
    }

    private void playLocal(int index, boolean shuffle) {
        List<File> files = new ArrayList<>();
        for (LocalMusic.Track track : localTracks) files.add(track.file);
        if (files.isEmpty()) return;
        // The queue of thousands of files is made off the main thread: an urn for each file.
        Utils.runOnBackgroundThread(() -> {
            boolean playing = LocalMusic.play(files, index, shuffle);
            Utils.runOnMainThread(() -> {
                if (activity.isFinishing()) return;
                if (playing) {
                    activity.finish();
                } else {
                    Toast.makeText(activity, text("Плеер ещё не готов. Откройте SoundCloud и попробуйте снова.",
                            "The player is not ready. Open SoundCloud and try again."), Toast.LENGTH_LONG).show();
                }
            });
        });
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
