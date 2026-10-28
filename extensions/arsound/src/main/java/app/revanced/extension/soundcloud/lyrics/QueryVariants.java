package app.revanced.extension.soundcloud.lyrics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The guesses of who sings what that a track gets: "ARTIST - TITLE", "TITLE - ARTIST", the uploader as the artist
 * with the whole title as the song, and a few more. The likeliest comes first and there are at most
 * {@value #MAX_VARIANTS}. Pure text work, so it runs in the JVM tests.
 */
final class QueryVariants {
    private static final int MAX_VARIANTS = 4;

    private static final Pattern SEPARATOR = Pattern.compile("\\s+[-–—‒−]\\s+|\\s+\\|\\s+|\\s+//\\s+|\\s+•\\s+|\\s*:\\s+");
    private static final Pattern DUO = Pattern.compile("\\s+(?:x|×|&|and|и)\\s+|\\s*[,;&]\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private QueryVariants() {
    }

    /**
     * @param rawTitle   The title as the uploader typed it.
     * @param cleanTitle The title without any tags.
     * @param artist     The uploader's name, cleaned.
     */
    static List<TrackQuery.Variant> of(String rawTitle, String cleanTitle, String artist) {
        List<TrackQuery.Variant> variants = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        for (String part : SEPARATOR.split(cleanTitle)) {
            if (!part.trim().isEmpty()) parts.add(part.trim());
        }
        if (parts.size() == 2) {
            String left = parts.get(0);
            String right = parts.get(1);
            // The side that looks like the uploader is the artist; the other order stays as the next guess.
            if (TextMatch.similarity(left, artist) >= TextMatch.similarity(right, artist)) {
                add(variants, left, right);
                add(variants, right, left);
            } else {
                add(variants, right, left);
                add(variants, left, right);
            }
            add(variants, artist, right);
        } else if (parts.size() > 2) {
            StringBuilder rest = new StringBuilder();
            for (int i = 1; i < parts.size(); i++) rest.append(i > 1 ? " " : "").append(parts.get(i));
            add(variants, parts.get(0), rest.toString());
            add(variants, artist, cleanTitle);
        } else {
            add(variants, artist, cleanTitle);
        }

        if (!variants.isEmpty()) {
            TrackQuery.Variant first = variants.get(0);
            // "A x B", "A & B", "A, B": the first one alone is how many records list the song.
            String[] artists = DUO.split(first.artist);
            if (artists.length > 1) add(variants, artists[0], first.track);
            // Brackets the title keeps for the sake of a song that has them in its name: "(Part 2)", "(2020)".
            String titleWithBrackets = TitleCleaner.clean(rawTitle, false).title;
            if (!titleWithBrackets.equals(cleanTitle) && parts.size() < 2) add(variants, first.artist, titleWithBrackets);
        }
        if (variants.size() > MAX_VARIANTS) variants = new ArrayList<>(variants.subList(0, MAX_VARIANTS));
        return variants;
    }

    /** Adds a guess unless it says the same as one already there. */
    private static void add(List<TrackQuery.Variant> variants, String artist, String track) {
        String cleanTrack = track == null ? "" : track.trim();
        if (cleanTrack.isEmpty()) return;
        String cleanArtist = artist == null ? "" : artist.trim();
        String key = TextMatch.normalize(cleanArtist) + "|" + TextMatch.normalize(cleanTrack);
        for (TrackQuery.Variant known : variants) {
            if ((TextMatch.normalize(known.artist) + "|" + TextMatch.normalize(known.track)).equals(key)) return;
        }
        variants.add(new TrackQuery.Variant(cleanArtist, cleanTrack));
    }
}
