package app.revanced.extension.soundcloud.local;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * An invisible screen that opens the system file picker, imports the picked audio files and,
 * if a playlist is given, adds them to that playlist on this device.
 */
@SuppressWarnings("unused")
public final class ImportActivity extends Activity {
    private static final String EXTRA_PLAYLIST_URN = "arsound_playlist_urn";
    private static final int REQUEST_PICK = 1;

    /**
     * Opens the file picker.
     *
     * @param playlistUrn The playlist to add the imported files to, or null to only import them.
     */
    public static void start(Context context, String playlistUrn) {
        try {
            Intent intent = new Intent(context, ImportActivity.class);
            if (playlistUrn != null) intent.putExtra(EXTRA_PLAYLIST_URN, playlistUrn);
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the import screen", ex);
        }
    }

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("audio/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQUEST_PICK);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the file picker", ex);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        finish();
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null) return;

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        Context context = getApplicationContext();
        String playlistUrn = getIntent().getStringExtra(EXTRA_PLAYLIST_URN);
        Toast.makeText(context, text("Импортирую…", "Importing…"), Toast.LENGTH_SHORT).show();
        Utils.runOnBackgroundThread(() -> {
            List<File> files = LocalMusic.importFileList(context, uris);
            // The saved playlist already shows every imported file.
            boolean addToPlaylist = playlistUrn != null && !SavedPlaylist.isSavedPlaylist(playlistUrn);
            if (addToPlaylist) {
                for (File file : files) LocalAdditions.add(playlistUrn, LocalAdditions.fileEntry(file));
            }
            String saved = SavedPlaylist.getUrn();
            Utils.runOnMainThread(() -> {
                if (addToPlaylist) LocalAdditions.notifyPlaylistChanged(playlistUrn);
                if (saved != null) LocalAdditions.notifyPlaylistChanged(saved);
                Toast.makeText(context, text("Импортировано: ", "Imported: ") + files.size(), Toast.LENGTH_SHORT).show();
            });
        });
    }
}
