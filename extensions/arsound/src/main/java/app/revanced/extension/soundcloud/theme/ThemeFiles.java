package app.revanced.extension.soundcloud.theme;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import app.revanced.extension.shared.Logger;

/** Reading and writing the small files of the themes: the assets the theme patch put in the app and the files built from them. */
final class ThemeFiles {
    /** The folder of Arsound's assets in the app, and of the files built from them in the app's files folder. */
    static final String ROOT = "arsound/";

    private ThemeFiles() {
    }

    /** Copies an asset to a file once; the file is kept while it has the same size. */
    static boolean copyAsset(Context context, String asset, File target) {
        try {
            // The size is known without reading the asset, unless the APK keeps it compressed.
            long size = assetSize(context, asset);
            if (size >= 0 && target.isFile() && target.length() == size) return true;
            try (InputStream input = context.getAssets().open(asset)) {
                byte[] bytes = readAll(input);
                if (!target.isFile() || target.length() != bytes.length) write(target, bytes);
            }
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not copy " + asset, ex);
            return false;
        }
    }

    /** The size of an asset, or -1 when it cannot be told without reading it (a compressed asset has no file descriptor). */
    private static long assetSize(Context context, String asset) {
        try (AssetFileDescriptor descriptor = context.getAssets().openFd(asset)) {
            return descriptor.getLength();
        } catch (Exception ex) {
            return -1;
        }
    }

    /** The text of a small file, or null when there is none. */
    static String readText(File file) {
        try {
            return new String(readAll(new FileInputStream(file)), "UTF-8");
        } catch (Exception ex) {
            return null;
        }
    }

    /** The text of an asset of the app. */
    static String readAsset(Context context, String asset) throws Exception {
        try (InputStream input = context.getAssets().open(asset)) {
            return new String(readAll(input), "UTF-8");
        }
    }

    static void write(File file, byte[] bytes) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
    }

    /** Everything the stream has; the stream is closed. */
    static byte[] readAll(InputStream stream) throws Exception {
        try (InputStream input = stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
