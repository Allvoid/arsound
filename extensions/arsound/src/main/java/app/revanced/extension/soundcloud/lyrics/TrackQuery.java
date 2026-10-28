package app.revanced.extension.soundcloud.lyrics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What SoundCloud knows about a track, turned into what LRCLIB asks for: an artist and a title.
 * <p>
 * SoundCloud has only the title the uploader typed and the uploader's name, and uploads are named as people like:
 * "ARTIST - TITLE (prod. X) [Official Audio]", "TITLE - ARTIST", "TITLE ft. SOMEONE", the artist as the uploader
 * and nothing in the title, a label as the uploader. So a track gets a few guesses, most likely first.
 */
final class TrackQuery {
    /** One guess of who sings what. */
    static final class Variant {
        final String artist;
        final String track;

        Variant(String artist, String track) {
            this.artist = artist;
            this.track = track;
        }
    }

    final String uploader;
    final long durationMs;
    /** The guesses, the likeliest first. */
    final List<Variant> variants;
    /** Guests named in the title, as another way the record may name the artist. */
    final List<String> featured;
    /** The title names another version (remix, slowed, live): its timings may differ from the record's. */
    final boolean versionTagged;
    /** The title names a copy at another speed (slowed, sped up, nightcore): the original's timings are stretched. */
    final boolean retimed;
    /** Changes with anything that changes the answer; a stored answer is good while it stays the same. */
    final String signature;

    private TrackQuery(String title, String uploader, long durationMs, List<Variant> variants, List<String> featured,
                       boolean versionTagged, boolean retimed) {
        this.uploader = uploader;
        this.durationMs = durationMs;
        this.variants = variants;
        this.featured = featured;
        this.versionTagged = versionTagged;
        this.retimed = retimed;
        // "|t1" marks answers found since the timings of such copies are stretched: older stored answers are not used.
        this.signature = TextMatch.fnv1aHex(TextMatch.normalize(title) + "|" + TextMatch.normalize(uploader) + "|"
                + durationMs / 1000 + (retimed ? "|t1" : ""));
    }

    static TrackQuery of(String title, String uploader, long durationMs) {
        String rawTitle = title == null ? "" : title;
        String rawUploader = uploader == null ? "" : uploader;
        TitleCleaner.Cleaned cleaned = TitleCleaner.clean(rawTitle, true);
        boolean retimed = TitleCleaner.isRetimed(rawTitle);
        boolean versionTagged = cleaned.versionTagged || retimed;
        List<Variant> variants = QueryVariants.of(rawTitle, cleaned.title, TitleCleaner.cleanUploader(rawUploader));
        return new TrackQuery(rawTitle, rawUploader, durationMs, variants, cleaned.featured, versionTagged, retimed);
    }

    /** Names the record may give the artist: what the guess says, the uploader and the guests of the title. */
    List<String> artistNames(Variant variant) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        names.add(variant.artist);
        seen.add(variant.artist);
        String channel = TitleCleaner.cleanUploader(uploader);
        if (seen.add(channel)) names.add(channel);
        for (String guest : featured) if (seen.add(guest)) names.add(guest);
        return names;
    }
}
