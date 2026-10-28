package app.revanced.extension.soundcloud.theme;

import android.content.Context;
import android.content.res.Resources;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.AtomicFiles;

/**
 * The pictures a theme recolours (the loading animation, the hearts behind page titles) as the app ships them.
 * Once a loader is attached, every read of those resources gives the recoloured copy of the theme that is on, and
 * tinting that again would darken it. So the first build of an app version, which runs before any loader exists in
 * the process, keeps the originals in files, and a later build in the same process (the palette of Monet changed)
 * tints those.
 */
final class OriginalAssets {
    private final File dir;
    /** True while no loader was added in this process: the resources still show the app's own files. */
    private final boolean pristine;

    /** @param pristine True while no loader was added in this process. */
    OriginalAssets(Context context, boolean pristine) {
        this.pristine = pristine;
        long installed = 0;
        try {
            installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not read when the app was installed", ex);
        }
        File parent = new File(context.getFilesDir(), ThemeFiles.ROOT + "theme");
        dir = new File(parent, "orig-" + installed);
        // The copies of an older version of the app are no use.
        File[] others = parent.listFiles((folder, name) -> name.startsWith("orig-") && !name.equals(dir.getName()));
        if (others != null) {
            for (File other : others) {
                File[] files = other.listFiles();
                if (files != null) for (File file : files) file.delete();
                other.delete();
            }
        }
    }

    byte[] loadingAnimation(Resources resources, int animation) throws Exception {
        File file = new File(dir, "loading_animation.json");
        byte[] kept = kept(file, true);
        if (kept != null) return kept;
        byte[] bytes = ThemeFiles.readAll(resources.openRawResource(animation));
        keep(file, bytes);
        return bytes;
    }

    /** The picture as PNG bytes, for a narrow or a wide screen; null if it could not be read. */
    byte[] picture(Resources source, int picture, String name, boolean wide) throws Exception {
        File file = new File(dir, name + (wide ? "-wide" : "") + ".png");
        byte[] kept = kept(file, false);
        if (kept != null) return kept;
        byte[] bytes = HeaderArtwork.original(source, picture);
        if (bytes != null) keep(file, bytes);
        return bytes;
    }

    /**
     * The kept copy, or null when there is none or it is damaged (a write that stopped half way): a damaged copy
     * is removed, so that it is not trusted at every build until the app is updated, and the original is read
     * again from the app.
     */
    private byte[] kept(File file, boolean animation) {
        if (!file.isFile()) return null;
        try {
            byte[] bytes = ThemeFiles.readAll(new FileInputStream(file));
            if (animation ? isLottie(bytes) : isPng(bytes)) return bytes;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Theme: the kept original " + file.getName() + " could not be read");
        }
        Logger.printInfo(() -> "Theme: the kept original " + file.getName() + " is damaged, reading it again");
        file.delete();
        return null;
    }

    /** True if this is a whole Lottie file: a cut-off one does not parse (it throws). */
    private static boolean isLottie(byte[] bytes) throws Exception {
        new JSONObject(new String(bytes, "UTF-8"));
        return true;
    }

    /** True if the PNG ends with its closing chunk, which the encoder writes last (4 bytes of length, "IEND", 4 of checksum). */
    static boolean isPng(byte[] bytes) {
        int end = bytes.length - 8;
        return end >= 8 && bytes[end] == 'I' && bytes[end + 1] == 'E' && bytes[end + 2] == 'N' && bytes[end + 3] == 'D';
    }

    private void keep(File file, byte[] bytes) {
        if (!pristine) {
            Logger.printInfo(() -> "Theme: no kept original of " + file.getName() + ", reading it from the themed app");
            return;
        }
        // Written aside and renamed, so a stop half way (no space left, the process killed) never leaves a cut-off
        // copy that the next builds would take for the original.
        try {
            dir.mkdirs();
            AtomicFiles.write(new File(dir, file.getName() + ".tmp"), file, bytes);
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not keep the original " + file.getName(), ex);
        }
    }
}
