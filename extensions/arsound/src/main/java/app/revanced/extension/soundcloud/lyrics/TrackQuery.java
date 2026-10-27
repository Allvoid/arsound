package app.revanced.extension.soundcloud.lyrics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final int MAX_VARIANTS = 4;

    /** (...) [...] {...} and the Japanese brackets. */
    private static final Pattern BRACKET = Pattern.compile(
            "[(\\[{【「『][^)\\]}】」』]*[)\\]}】」』]");
    /** Words of a tag that says nothing about the song. */
    private static final Pattern JUNK = Pattern.compile("\\b(?:official|video|audio|lyrics?|lyric video|visuali[sz]er"
            + "|music video|mv|hd|hq|4k|remaster(?:ed)?(?:\\s*\\d{4})?|prod\\.?(?:\\s+by)?|produced by|free download"
            + "|out now|premiere|snippet|clip|explicit|clean|full song|full version|new song|new music|video oficial"
            + "|audio oficial|клип|премьера|текст|karaoke)\\b", FLAGS);
    /** Words of a tag that name another version of the song. */
    private static final Pattern VERSION = Pattern.compile("\\b(?:remix|mix|edit|vip|sped up|speed up|slowed|reverb"
            + "|nightcore|live|acoustic|instrumental|cover|bootleg|flip|version)\\b", FLAGS);
    /** Versions that change the speed, so the timings of the original do not fit. */
    private static final Pattern RETIMED = Pattern.compile("\\b(?:sped up|speed up|slowed|nightcore|8d)\\b", FLAGS);
    private static final Pattern FEAT_WORD = Pattern.compile("\\b(?:feat|ft|featuring)\\b", FLAGS);
    /** "feat. A & B" outside brackets, up to a closing bracket, a spaced dash or the end. */
    private static final Pattern FEAT = Pattern.compile("\\s*[(\\[]?\\s*\\b(?:feat\\.?|ft\\.?|featuring|w/)\\s+([^)\\]]*?)"
            + "\\s*(?:[)\\]]|(?=\\s[-–—|]\\s)|$)", FLAGS);
    /** The guests inside a tag: "(feat. A &amp; B)". */
    private static final Pattern FEAT_INSIDE = Pattern.compile("\\b(?:feat\\.?|ft\\.?|featuring)\\s+(.*)$", FLAGS);
    private static final Pattern PRODUCER =Pattern.compile("\\s+(?:prod\\.?\\s+by|produced\\s+by|prod\\.)\\s+.*$", FLAGS);
    private static final Pattern NAME_SPLIT = Pattern.compile("\\s*(?:,|&|\\band\\b|\\bи\\b|\\bx\\b)\\s*", FLAGS);
    private static final Pattern SEPARATOR = Pattern.compile("\\s+[-–—‒−]\\s+|\\s+\\|\\s+|\\s+//\\s+|\\s+•\\s+|\\s*:\\s+");
    private static final Pattern TOPIC = Pattern.compile("\\s*[-–]\\s*topic$", FLAGS);
    private static final Pattern UPLOADER_WORDS = Pattern.compile("\\b(?:vevo|official|records?|music|archive|channel|tv|hd"
            + "|network|entertainment|label|lyrics?)\\b", FLAGS);
    private static final Pattern DUO = Pattern.compile("\\s+(?:x|×|&|and|и)\\s+|\\s*[,;&]\\s*", FLAGS);
    private static final Pattern SPACES = Pattern.compile("\\s+");

    final String title;
    final String uploader;
    final long durationMs;
    /** The guesses, the likeliest first. */
    final List<Variant> variants;
    /** Guests named in the title, as another way the record may name the artist. */
    final List<String> featured;
    /** The title names another version (remix, slowed, live): its timings may differ from the record's. */
    final boolean versionTagged;
    /** Changes with anything that changes the answer; a stored answer is good while it stays the same. */
    final String signature;

    private TrackQuery(String title, String uploader, long durationMs, List<Variant> variants, List<String> featured,
                       boolean versionTagged) {
        this.title = title;
        this.uploader = uploader;
        this.durationMs = durationMs;
        this.variants = variants;
        this.featured = featured;
        this.versionTagged = versionTagged;
        this.signature = TextMatch.hash(TextMatch.normalize(title) + "|" + TextMatch.normalize(uploader) + "|"
                + durationMs / 1000);
    }

    static TrackQuery of(String title, String uploader, long durationMs) {
        String rawTitle = title == null ? "" : title;
        String rawUploader = uploader == null ? "" : uploader;
        List<String> featured = new ArrayList<>();
        boolean[] tagged = new boolean[1];
        String cleanTitle = strip(rawTitle, true, featured, tagged);
        if (RETIMED.matcher(rawTitle).find()) tagged[0] = true;
        String artist = cleanUploader(rawUploader);

        List<Variant> variants = new ArrayList<>();
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
            Variant first = variants.get(0);
            // "A x B", "A & B", "A, B": the first one alone is how many records list the song.
            String[] artists = DUO.split(first.artist);
            if (artists.length > 1) add(variants, artists[0], first.track);
            // Brackets the title keeps for the sake of a song that has them in its name: "(Part 2)", "(2020)".
            String withBrackets = strip(rawTitle, false, new ArrayList<String>(), new boolean[1]);
            if (!withBrackets.equals(cleanTitle) && parts.size() < 2) add(variants, first.artist, withBrackets);
        }
        if (variants.size() > MAX_VARIANTS) variants = new ArrayList<>(variants.subList(0, MAX_VARIANTS));
        return new TrackQuery(rawTitle, rawUploader, durationMs, variants, featured, tagged[0]);
    }

    private static void add(List<Variant> variants, String artist, String track) {
        String cleanTrack = track == null ? "" : track.trim();
        if (cleanTrack.isEmpty()) return;
        String cleanArtist = artist == null ? "" : artist.trim();
        String key = TextMatch.normalize(cleanArtist) + "|" + TextMatch.normalize(cleanTrack);
        for (Variant known : variants) {
            if ((TextMatch.normalize(known.artist) + "|" + TextMatch.normalize(known.track)).equals(key)) return;
        }
        variants.add(new Variant(cleanArtist, cleanTrack));
    }

    /**
     * The title without the tags uploaders add: [Official Video], (prod. X), (feat. Y), (Slowed + Reverb). Names of
     * guests go into {@code featured}; a tag naming another version sets {@code tagged[0]}.
     *
     * @param dropAllBrackets Also drop brackets of unknown content ("[free for profit]"), not only the known tags.
     */
    private static String strip(String title, boolean dropAllBrackets, List<String> featured, boolean[] tagged) {
        Matcher matcher = BRACKET.matcher(title);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String whole = matcher.group();
            String inner = whole.substring(1, whole.length() - 1);
            String replacement;
            if (JUNK.matcher(inner).find() || FEAT_WORD.matcher(inner).find()) {
                Matcher guests = FEAT_INSIDE.matcher(inner);
                if (guests.find()) addGuests(featured, guests.group(1));
                replacement = " ";
            } else if (VERSION.matcher(inner).find()) {
                tagged[0] = true;
                replacement = " ";
            } else {
                replacement = dropAllBrackets ? " " : whole;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        String text = result.toString();
        Matcher guests = FEAT.matcher(text);
        if (guests.find()) {
            addGuests(featured, guests.group(1));
            text = FEAT.matcher(text).replaceAll(" ");
        }
        text = PRODUCER.matcher(text).replaceAll("");
        return trimEdges(SPACES.matcher(text).replaceAll(" "));
    }

    /** A title without the tags uploaders add; for comparing a record's name with the title. */
    static String withoutTags(String title) {
        return strip(title == null ? "" : title, true, new ArrayList<String>(), new boolean[1]);
    }

    private static void addGuests(List<String> featured, String names) {
        for (String name : NAME_SPLIT.split(names)) {
            String guest = name.trim();
            if (!guest.isEmpty()) featured.add(guest);
        }
    }

    /** The uploader's name without the words a channel adds to it ("Artist - Topic", "ArtistVEVO", "Artist Official"). */
    static String cleanUploader(String uploader) {
        String text = uploader.replace('_', ' ');
        text = TOPIC.matcher(text).replaceAll("");
        text = UPLOADER_WORDS.matcher(text).replaceAll(" ");
        text = trimEdges(SPACES.matcher(text).replaceAll(" "));
        return text.isEmpty() ? uploader.trim() : text;
    }

    private static String trimEdges(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && " -–—|•_,".indexOf(text.charAt(start)) >= 0) start++;
        while (end > start && " -–—|•_,".indexOf(text.charAt(end - 1)) >= 0) end--;
        return text.substring(start, end);
    }

    /** Names the record may give the artist: what the guess says, the uploader and the guests of the title. */
    List<String> artistNames(Variant variant) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        names.add(variant.artist);
        seen.add(variant.artist);
        String channel = cleanUploader(uploader);
        if (seen.add(channel)) names.add(channel);
        for (String guest : featured) if (seen.add(guest)) names.add(guest);
        return names;
    }
}
