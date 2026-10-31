package app.revanced.extension.soundcloud.recommendations;

import java.util.List;

/** The title and length of a song, as SoundCloud or the user's dislikes name it. */
final class SongRef {
    final String title;
    final long durationMs;

    SongRef(String title, long durationMs) {
        this.title = title;
        this.durationMs = durationMs;
    }

    /** The fingerprint without merging edited versions, made once: a song is compared with many. */
    private SongFingerprint plain;

    /** How this song is compared with others. */
    SongFingerprint fingerprint(boolean mergeEdited) {
        if (!mergeEdited && plain != null) return plain;
        SongFingerprint made = new SongFingerprint(SongKeys.key(title, mergeEdited), durationMs, SongKeys.isEdited(title), mergeEdited);
        if (!mergeEdited) plain = made;
        return made;
    }

    /**
     * The same song under two titles and lengths, whatever the user chose for the lists: a remix or another speed is
     * another song here. For the "For you" playlist.
     */
    boolean isSameSongAs(SongRef other) {
        return fingerprint(false).sameAs(other.fingerprint(false));
    }

    /** True if a copy of this song is among these songs; a song without a title or a length has no copies. */
    boolean isCopyOfAny(List<SongRef> songs) {
        if (title == null || title.isEmpty() || durationMs <= 0) return false;
        for (SongRef song : songs) {
            if (song.isSameSongAs(this)) return true;
        }
        return false;
    }
}
