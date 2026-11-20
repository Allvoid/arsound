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
     * @param reportedSize The size the system names, or -1 when it names none (the limit is assumed then).
     * @param usable       The free bytes where the copy goes.
     */
    static boolean hasRoom(long reportedSize, long usable) {
        long needed = reportedSize >= 0 ? Math.min(reportedSize, WallpaperStore.MAX_OWN_BYTES) : WallpaperStore.MAX_OWN_BYTES;
        return usable >= needed + RESERVE_BYTES;
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
