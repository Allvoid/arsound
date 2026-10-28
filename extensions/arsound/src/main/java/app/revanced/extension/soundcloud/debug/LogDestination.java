package app.revanced.extension.soundcloud.debug;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import java.io.IOException;
import java.io.OutputStream;

import app.revanced.extension.soundcloud.shared.Lang;

/** Where the log file goes. Nothing is created until {@link #open()}, so a failed attempt can be undone. */
interface LogDestination {
    OutputStream open() throws IOException;

    /** Where the file is, for the user: a path in words. */
    String shownPath();

    /** Makes the written file visible. Returns the address to share it with, or null if it cannot be shared. */
    Uri finish();

    /** Removes a half-written file, so failed attempts do not pile up. */
    void discard();

    static LogDestination create(Context context, String fileName) {
        return Build.VERSION.SDK_INT >= 29
                ? new DownloadsDestination(context, fileName)
                : new AppFilesDestination(context, fileName);
    }

    /** Download folder through MediaStore: needs no permission. */
    final class DownloadsDestination implements LogDestination {
        private final Context context;
        private final String fileName;
        private Uri uri;

        DownloadsDestination(Context context, String fileName) {
            this.context = context;
            this.fileName = fileName;
        }

        @Override
        public OutputStream open() throws IOException {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            // Hidden from Downloads until the whole file is written.
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            uri = context.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("The file could not be created");
            OutputStream output = context.getContentResolver().openOutputStream(uri);
            if (output == null) throw new IOException("The file could not be opened");
            return output;
        }

        @Override
        public String shownPath() {
            return Lang.text("Загрузки/", "Downloads/") + fileName;
        }

        @Override
        public Uri finish() {
            android.content.ContentValues done = new android.content.ContentValues();
            done.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(uri, done, null, null);
            return uri;
        }

        @Override
        public void discard() {
            try {
                if (uri != null) context.getContentResolver().delete(uri, null, null);
            } catch (Exception ex) {
                app.revanced.extension.shared.Logger.printInfo(() -> "Could not remove the unfinished log file: " + ex);
            }
        }
    }

    /** The app's own folder: before Android 10 the app cannot write to Downloads without a permission. */
    final class AppFilesDestination implements LogDestination {
        private final java.io.File file;

        AppFilesDestination(Context context, String fileName) {
            java.io.File base = context.getExternalFilesDir(null);
            file = new java.io.File(base != null ? base : context.getFilesDir(), fileName);
        }

        @Override
        public OutputStream open() throws IOException {
            return new java.io.FileOutputStream(file);
        }

        @Override
        public String shownPath() {
            return file.getAbsolutePath();
        }

        @Override
        public Uri finish() {
            return null;
        }

        @Override
        public void discard() {
            if (file.exists() && !file.delete()) {
                app.revanced.extension.shared.Logger.printInfo(() -> "Could not remove " + file);
            }
        }
    }
}
