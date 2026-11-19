package app.revanced.extension.soundcloud.stats;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import app.revanced.extension.shared.AppStorage;
import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Shares the card of the year in review as a PNG picture. The app has no file provider of its own, so the picture is
 * saved into the Pictures folder through the media store (Android 10 and later, no permission needed) and its address
 * is handed to the share sheet. Before Android 10 the picture is only saved into the app's own folder, and the user
 * is told where.
 */
public final class ReviewShare {
    private ReviewShare() {
    }

    /**
     * Saves the picture and opens the share sheet. Returns at once: the work is done in the background.
     *
     * @param name The file name without the extension, for example {@code arsound-2026}.
     */
    public static void share(Activity activity, Bitmap card, String name) {
        Context context = activity.getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            try {
                if (Build.VERSION.SDK_INT < 29) {
                    File file = saveIntoAppFolder(context, card, name);
                    // The user is told the whole address: the app folder is not easy to find by hand.
                    Utils.runOnMainThread(() -> Utils.showToastLong(text(
                            "Картинка сохранена здесь: " + file.getAbsolutePath() + " (Android до 10 не даёт поделиться ею).",
                            "The picture was saved here: " + file.getAbsolutePath() + " (before Android 10 it cannot be shared).")));
                    return;
                }
                Uri uri = save(context, card, name);
                Utils.runOnMainThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    Intent send = new Intent(Intent.ACTION_SEND)
                            .setType("image/png")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    send.setClipData(ClipData.newUri(activity.getContentResolver(), name, uri));
                    activity.startActivity(Intent.createChooser(send, text("Поделиться итогами", "Share the review")));
                });
            } catch (Exception ex) {
                Logger.printException(() -> "Year in review: the picture was not saved", ex);
                Utils.runOnMainThread(() -> Utils.showToastLong(text("Картинку сохранить не удалось", "The picture was not saved")));
            }
        });
    }

    /** Before Android 10: the picture goes into the app's own folder. @return The file written. */
    private static File saveIntoAppFolder(Context context, Bitmap card, String name) throws Exception {
        File base = AppStorage.externalFilesDir(context, null);
        File file = new File(base != null ? base : context.getFilesDir(), name + ".png");
        try (OutputStream output = new FileOutputStream(file)) {
            card.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        return file;
    }

    /** Android 10 and later. @return The address of the saved picture in the Pictures folder. */
    private static Uri save(Context context, Bitmap card, String name) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        // The same review shared again replaces its earlier picture, so the gallery does not get name (1), (2)...
        // A picture of another app with that name cannot be removed: then a copy is added, as before.
        try {
            resolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{name + ".png", Environment.DIRECTORY_PICTURES + "/Arsound/%"});
        } catch (RuntimeException ignored) {
            // Not ours to remove.
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name + ".png");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Arsound");
        // Hidden from the gallery until the whole picture is written.
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("The picture could not be created");
        try {
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null || !card.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new Exception("The picture could not be written");
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, done, null, null) < 1) throw new Exception("The picture could not be finished");
        } catch (Exception ex) {
            // No half-written row is left in the gallery.
            resolver.delete(uri, null, null);
            throw ex;
        }
        return uri;
    }
}
