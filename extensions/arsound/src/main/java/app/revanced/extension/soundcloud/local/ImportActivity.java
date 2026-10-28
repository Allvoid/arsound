package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * An invisible screen that opens the system file picker, imports the picked audio files and,
 * if a playlist is given, adds them to that playlist on this device.
 * <p>
 * It also picks a picture from the gallery as the cover of an imported track or of a playlist.
 */
@SuppressWarnings("unused")
public final class ImportActivity extends LocalActivity {
    private static final String EXTRA_PLAYLIST_URN = "arsound_playlist_urn";
    private static final String EXTRA_COVER_FILE = "arsound_cover_file";
    private static final String EXTRA_COVER_PLAYLIST = "arsound_cover_playlist";
    private static final int REQUEST_PICK = 1;
    private static final int REQUEST_COVER = 2;
    private static final int REQUEST_FOLDER = 3;
    private static final String EXTRA_WATCH_FOLDER = "arsound_watch_folder";
    /** A picked picture bigger than this is refused: it is read into memory before it is made smaller. */
    private static final int MAX_PICTURE_BYTES = 32 * 1024 * 1024;
    /**
     * Most picked files whose access is kept for the copy. The system keeps few such grants per app (128
     * before Android 11) and drops the oldest first, which would be the watched folder.
     */
    private static final int MAX_KEPT_ACCESS = 100;
    /** Files whose kept access an import in progress still needs. */
    private static final Set<Uri> COPYING = ConcurrentHashMap.newKeySet();

    /** Opens the folder picker for the watched folder. */
    public static void pickWatchFolder(Context context) {
        startForCover(context, EXTRA_WATCH_FOLDER, "1");
    }

    /**
     * Opens the file picker.
     *
     * @param playlistUrn The playlist to add the imported files to, or null to only import them.
     */
    public static void start(Context context, String playlistUrn) {
        try {
            Intent intent = newIntent(context);
            if (playlistUrn != null) intent.putExtra(EXTRA_PLAYLIST_URN, playlistUrn);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the import screen", ex);
        }
    }

    /** An intent for this screen; it starts a task of its own when the caller is not an activity. */
    private static Intent newIntent(Context context) {
        Intent intent = new Intent(context, ImportActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
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
            context.startActivity(newIntent(context).putExtra(extra, value));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the cover picker", ex);
        }
    }

    private boolean isCoverPick() {
        return getIntent().hasExtra(EXTRA_COVER_FILE) || getIntent().hasExtra(EXTRA_COVER_PLAYLIST);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        if (isCoverPick()) {
            openGallery();
            return;
        }
        if (getIntent().hasExtra(EXTRA_WATCH_FOLDER)) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not open the folder picker", ex);
                finish();
            }
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
        if (Build.VERSION.SDK_INT < 33 || picker.resolveActivity(getPackageManager()) == null) {
            picker = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        }
        try {
            startActivityForResult(picker, REQUEST_COVER);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the gallery", ex);
            finish();
        }
    }

    /** Reads the whole stream, or returns null if it is longer than the limit. */
    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        // Sized by what the stream says is left, so a picture is held once, not copied while it grows.
        byte[] data = new byte[Math.min(Math.max(input.available(), 64 * 1024), limit)];
        int size = 0;
        while (true) {
            if (size == data.length) {
                int next = input.read();
                if (next == -1) return data;
                if (size >= limit) return null;
                data = Arrays.copyOf(data, (int) Math.min((long) size * 2, limit));
                data[size++] = (byte) next;
            }
            int read = input.read(data, size, data.length - size);
            if (read == -1) return size == data.length ? data : Arrays.copyOf(data, size);
            size += read;
        }
    }

    private void onCoverPicked(Uri uri) {
        Context context = getApplicationContext();
        String file = getIntent().getStringExtra(EXTRA_COVER_FILE);
        String playlistUrn = getIntent().getStringExtra(EXTRA_COVER_PLAYLIST);
        Utils.runOnBackgroundThread(() -> {
            boolean saved = false;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                // Read at once: the access to the picture goes with this screen. LocalCovers makes a big picture smaller.
                byte[] image = input == null ? null : readLimited(input, MAX_PICTURE_BYTES);
                if (image == null) {
                    Logger.printInfo(() -> "The picked picture is missing or bigger than " + MAX_PICTURE_BYTES + " bytes");
                } else {
                    saved = file != null
                            ? LocalCovers.setUserCover(new File(file), image)
                            : LocalCovers.setPlaylistCover(playlistUrn, image);
                }
            } catch (Exception | OutOfMemoryError ex) {
                Logger.printException(() -> "Could not read the picked picture", ex);
            }
            boolean done = saved;
            Utils.runOnMainThread(() -> {
                if (playlistUrn != null) {
                    LocalAdditions.notifyPlaylistChanged(playlistUrn);
                    // The open header is not bound again by the list: its playlist did not change.
                    PlaylistHeader.redraw(playlistUrn);
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
        if (requestCode == REQUEST_FOLDER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) onFolderPicked(data.getData());
        } else if (requestCode == REQUEST_COVER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) onCoverPicked(data.getData());
        } else if (requestCode == REQUEST_PICK) {
            if (resultCode == RESULT_OK && data != null) onFilesPicked(data);
        }
    }

    private void onFolderPicked(Uri folder) {
        try {
            getContentResolver().takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not keep the folder access", ex);
        }
        WatchFolder.setFolder(getApplicationContext(), folder);
    }

    private void onFilesPicked(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        Context context = getApplicationContext();
        String playlistUrn = getIntent().getStringExtra(EXTRA_PLAYLIST_URN);
        List<Uri> kept = keepAccess(uris);
        Toast.makeText(context, text("Импортирую…", "Importing…"), Toast.LENGTH_SHORT).show();
        Utils.runOnBackgroundThread(() -> {
            List<File> files;
            try {
                files = LocalMusic.importFileList(context, uris);
            } finally {
                releaseAccess(context, kept);
            }
            // The saved playlist already shows every imported file.
            boolean addToPlaylist = playlistUrn != null && !SavedPlaylist.isSavedPlaylist(playlistUrn);
            if (addToPlaylist) {
                for (File file : files) LocalAdditions.add(playlistUrn, LocalAdditions.fileEntry(file));
            }
            String saved = SavedPlaylist.getUrn();
            Utils.runOnMainThread(() -> {
                if (addToPlaylist) LocalAdditions.notifyPlaylistChanged(playlistUrn);
                if (saved != null) LocalAdditions.notifyPlaylistChanged(saved);
                // Files that could not be copied are told, not silently left out.
                String count = files.size() < uris.size() ? files.size() + text(" из ", " of ") + uris.size() : String.valueOf(files.size());
                Toast.makeText(context, text("Импортировано: ", "Imported: ") + count, Toast.LENGTH_SHORT).show();
            });
        });
    }

    /**
     * The system lets this screen read the picked files only while it is open. It is closed at once and
     * the copy goes on in the background, so the later files could not be read. A persistable grant
     * outlives the screen; it is given back when the copy is over.
     *
     * @return The files whose access is kept: at most {@link #MAX_KEPT_ACCESS} of the first ones.
     */
    private List<Uri> keepAccess(List<Uri> uris) {
        // Access kept by an import that the system cut short (the app was closed during the copy) is given back
        // now; only the watched folder, a folder grant, keeps its access for good.
        try {
            for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
                Uri uri = permission.getUri();
                if (permission.isReadPermission() && !DocumentsContract.isTreeUri(uri)
                        && !COPYING.contains(uri)) {
                    getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not give back old file access: " + ex);
        }
        List<Uri> kept = new ArrayList<>();
        int failed = 0;
        for (Uri uri : uris.subList(0, Math.min(uris.size(), MAX_KEPT_ACCESS))) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                kept.add(uri);
                COPYING.add(uri);
            } catch (Exception ex) {
                // Not every provider gives access that outlives the screen.
                failed++;
            }
        }
        int count = failed;
        if (count > 0) Logger.printInfo(() -> "Access to " + count + " picked files cannot be kept");
        return kept;
    }

    private static void releaseAccess(Context context, List<Uri> kept) {
        for (Uri uri : kept) {
            COPYING.remove(uri);
            try {
                context.getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not give back the access to " + uri + ": " + ex);
            }
        }
    }
}
