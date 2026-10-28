package app.revanced.extension.soundcloud.local;

import app.revanced.extension.soundcloud.shared.Lang;

/** Words for the messages of the local music that need more than a plain translation. */
final class LocalText {
    private LocalText() {
    }

    /** "1 трек", "2 трека", "5 треков", "1 track", "2 tracks". */
    static String tracks(int count) {
        if (!Lang.russian()) return count + (count == 1 ? " track" : " tracks");
        int lastTwo = count % 100, last = count % 10;
        String word = last == 1 && lastTwo != 11 ? "трек"
                : last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? "трека" : "треков";
        return count + " " + word;
    }
}
