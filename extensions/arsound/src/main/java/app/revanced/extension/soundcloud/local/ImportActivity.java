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
 * <p>
 * It also picks a picture from the gallery as the cover of an imported track or of a playlist.
 */
@SuppressWarnings("unused")
public final class ImportActivity extends Activity {
    private static final String EXTRA_PLAYLIST_URN = "arsound_playlist_urn";
    private static final String EXTRA_COVER_FILE = "arsound_cover_file";
    private static final String EXTRA_COVER_PLAYLIST = "arsound_cover_playlist";
    private static final int REQUEST_PICK = 1;
    private static final int REQUEST_COVER = 2;

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

    /** Opens the gallery to pick the cover of an imported file. */
    public static void pickTrackCover(Context context, File audio) {
        startForCover(context, EXTRA_COVER_FILE, audio.getPath());
    }

    /** Opens the gallery to pick the cover of a playlist on this device. */
    public static void pickPlaylistCover(Context context, String playlistUrn) {
        startForCover(context, EXTRA_COVER_PLAYLIST, playlistUrn);
    }

    private static void startForCover(Context context, String extra, String value) {
        try {
            Intent intent = new Intent(context, ImportActivity.class).putExtra(extra, value);
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the cover picker", ex);
        }
    }

    private boolean isCoverPick() {
        return getIntent().hasExtra(EXTRA_COVER_FILE) || getIntent().hasExtra(EXTRA_COVER_PLAYLIST);
    }

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        if (isCoverPick()) {
            openGallery();
            return;
        }
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

    /** The system photo picker needs no permission; older systems get the "choose an image" screen. */
    private void openGallery() {
        Intent picker = new Intent("android.provider.action.PICK_IMAGES");
        if (android.os.Build.VERSION.SDK_INT < 33 || picker.resolveActivity(getPackageManager()) == null) {
            picker = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        }
        try {
            startActivityForResult(picker, REQUEST_COVER);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the gallery", ex);
            finish();
        }
    }

    private void onCoverPicked(Uri uri) {
        Context context = getApplicationContext();
        String file = getIntent().getStringExtra(EXTRA_COVER_FILE);
        String playlistUrn = getIntent().getStringExtra(EXTRA_COVER_PLAYLIST);
        Utils.runOnBackgroundThread(() -> {
            boolean saved = false;
            try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                byte[] image = output.toByteArray();
                saved = file != null
                        ? LocalCovers.setUserCover(new File(file), image)
                        : LocalCovers.setPlaylistCover(playlistUrn, image);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not read the picked picture", ex);
            }
            boolean done = saved;
            Utils.runOnMainThread(() -> {
                if (playlistUrn != null) {
                    LocalAdditions.notifyPlaylistChanged(playlistUrn);
                    if (done) PlaylistHeader.showCover(playlistUrn, LocalCovers.playlistCoverUrl(playlistUrn));
                }
                if (file != null) LocalAdditions.notifyTrackChanged(new File(file));
                Toast.makeText(context, done
                        ? text("Обложка сохранена на этом телефоне", "The cover is saved on this phone")
                        : text("Не удалось сохранить обложку", "Could not save the cover"), Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        finish();
        if (requestCode == REQUEST_COVER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) onCoverPicked(data.getData());
            return;
        }
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
