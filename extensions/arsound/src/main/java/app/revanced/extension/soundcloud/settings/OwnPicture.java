package app.revanced.extension.soundcloud.settings;

import app.revanced.extension.soundcloud.wallpaper.WallpaperStore;

/** The rules and the words of picking an own wallpaper file: its size limit and the room its copy needs. */
final class OwnPicture {
    /** Room left on the storage after the copy: the baked picture and the system's own needs. */
    static final long RESERVE_BYTES = 64L * 1024 * 1024;

    private OwnPicture() {
    }

    /** The size limit in whole megabytes, as the message names it. */
    static long limitMegabytes() {
        return WallpaperStore.MAX_OWN_BYTES / (1024 * 1024);
    }

    /**
     * Whether the storage has room for the copy of the picked file.
     *
     * @param reportedSize The size the system names, or -1 when it names none.
     * @param usable       The free bytes where the copy goes, or -1 when they could not be read.
     * @return False only when both are known and the copy does not fit; what is not known is let through, and a full
     * storage during the copy is caught as such ({@link #isNoSpace}).
     */
    static boolean hasRoom(long reportedSize, long usable) {
        if (reportedSize < 0 || usable < 0) return true;
        return usable >= Math.min(reportedSize, WallpaperStore.MAX_OWN_BYTES) + RESERVE_BYTES;
    }

    /** The free bytes under the folder, or -1 when there is no folder or it is not there (the system answers 0 then). */
    static long usableOf(java.io.File folder) {
        return folder == null ? -1 : usableFrom(folder.exists(), folder.getUsableSpace());
    }

    /** The reading of the system for a folder: 0 means a full storage only when the folder is there. */
    static long usableFrom(boolean exists, long reading) {
        return exists ? reading : -1;
    }

    /** Whether a failure of the copy came from a full storage. */
    static boolean isNoSpace(Throwable failure) {
        for (int depth = 0; failure != null && depth < 6; depth++, failure = failure.getCause()) {
            String message = failure.getMessage();
            if (message != null && (message.contains("ENOSPC") || message.toLowerCase().contains("no space left"))) return true;
        }
        return false;
    }

    static String tooBigText(boolean russian) {
        return russian
                ? "Файл больше " + limitMegabytes() + " МБ: возьмите покороче или полегче"
                : "The file is over " + limitMegabytes() + " MB: pick a shorter or lighter one";
    }

    static String noRoomText(boolean russian) {
        return russian
                ? "Не хватает места на телефоне, чтобы взять этот файл: освободите место и попробуйте ещё раз"
                : "Not enough room on the phone to take this file: free some space and try again";
    }
}
