package app.revanced.extension.soundcloud.lyrics.transcribe;

import app.revanced.extension.soundcloud.shared.Lang;

/** Sizes of files for the texts of the recognition, in the units the size of a download is quoted in. */
public final class Sizes {
    private Sizes() {
    }

    /** "375 МБ", "1,2 ГБ": decimal units, as the size of a download is told everywhere. */
    public static String format(long bytes) {
        if (bytes >= 1_000_000_000L) {
            String number = String.format(java.util.Locale.ROOT, "%.1f", bytes / 1e9);
            return Lang.russian() ? number.replace('.', ',') + " ГБ" : number + " GB";
        }
        long megabytes = Math.max(0, Math.round(bytes / 1e6));
        if (bytes > 0 && megabytes == 0) return Lang.russian() ? "меньше 1 МБ" : "under 1 MB";
        return megabytes + (Lang.russian() ? " МБ" : " MB");
    }
}
