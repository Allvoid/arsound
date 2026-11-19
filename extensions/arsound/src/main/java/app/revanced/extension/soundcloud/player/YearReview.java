package app.revanced.extension.soundcloud.player;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import app.revanced.extension.soundcloud.recommendations.SongNames;

/**
 * "Year in review": a summary of a year, or of any period, out of the listens of the statistics, as on the desktop.
 * Counted on the phone, no network. Pure counting, no Android: the listens and the names of the artists come in.
 * <p>
 * The time is the time of all listens, a play is a listen of {@link ListeningStats#PLAY_THRESHOLD_MS} or more.
 */
public final class YearReview {
    /** How many places the tops have. */
    public static final int TOP = 5;
    /** How many of the new artists are named. */
    public static final int NEW_NAMES = 3;

    private YearReview() {
    }

    /** A track of the top: how many times it was played and for how long. The title and the artist are filled in later. */
    public static final class TrackPlays {
        public final String urn;
        public int plays;
        public long playedMs;
        public String title;
        public String artist;

        TrackPlays(String urn) {
            this.urn = urn;
        }
    }

    /** An artist of the top and the time listened to the artist. */
    public static final class ArtistTime {
        public final String name;
        public final long playedMs;

        ArtistTime(String name, long playedMs) {
            this.name = name;
            this.playedMs = playedMs;
        }
    }

    public static final class Result {
        public long totalMs;
        public int plays;
        /** Different tracks listened to. */
        public int tracks;
        /** Different artists listened to. */
        public int artists;
        /** Listens of the period whose artist is unknown: they count in the time, not in the artists. */
        public int unknownArtistPlays;
        /** The most played first, then the longer listened; at most {@link #TOP}. */
        public final List<TrackPlays> topTracks = new ArrayList<>();
        /** The most listened first; at most {@link #TOP}. */
        public final List<ArtistTime> topArtists = new ArrayList<>();
        /** The day with the most time, in the zone of the review; {@link #busiestMs} is 0 when there was no listening. */
        public int busiestYear;
        public int busiestMonth;
        public int busiestDay;
        public long busiestMs;
        /** The most days in a row with a listen. */
        public int longestStreak;
        /** Artists first heard in the period: no listen of theirs before it. */
        public int newArtists;
        /** The most listened of them first; at most {@link #NEW_NAMES}. */
        public final List<String> newArtistNames = new ArrayList<>();
        /** The years that have at least one listen, the newest first (of all the listens given, not only the period). */
        public final List<Integer> years = new ArrayList<>();

        public boolean isEmpty() {
            return plays == 0 && totalMs == 0;
        }
    }

    /**
     * The review of [from, to) out of {@code listens}, which may hold listens from before {@code from} too (they say
     * which artists are new) and after {@code to} (ignored).
     *
     * @param artistOf The artist of a track by its urn; null or empty when unknown: such listens count in the time and
     *                 the tracks, not in the artists.
     */
    static Result build(List<ListenRecord> listens, long from, long to, Function<String, String> artistOf, TimeZone zone) {
        Result result = new Result();
        // Always the Gregorian calendar: the default locale may give another one (the Buddhist years of Thailand).
        Calendar calendar = new GregorianCalendar(zone);
        Map<String, String> artists = new HashMap<>();
        Set<String> heardBefore = new HashSet<>();
        Map<String, TrackPlays> perTrack = new HashMap<>();
        Map<String, Long> perArtist = new HashMap<>();
        // The spellings of one artist, with the time listened under each: the longest listened names the artist.
        Map<String, Map<String, Long>> spellings = new HashMap<>();
        Map<Long, long[]> perDay = new TreeMap<>();
        Set<Integer> years = new TreeSet<>();
        for (ListenRecord listen : listens) {
            calendar.setTimeInMillis(listen.start);
            years.add(calendar.get(Calendar.YEAR));
            String artistKey = artistKey(artists, listen.urn, artistOf);
            if (listen.start < from) {
                if (artistKey != null) heardBefore.add(artistKey);
                continue;
            }
            if (listen.start >= to) continue;
            result.totalMs += listen.playedMs;
            boolean play = listen.playedMs >= ListeningStats.PLAY_THRESHOLD_MS;
            if (play) result.plays++;
            TrackPlays track = perTrack.get(listen.urn);
            if (track == null) perTrack.put(listen.urn, track = new TrackPlays(listen.urn));
            if (play) track.plays++;
            track.playedMs += listen.playedMs;
            if (artistKey != null) {
                Long sum = perArtist.get(artistKey);
                perArtist.put(artistKey, (sum == null ? 0 : sum) + listen.playedMs);
                String name = artists.get(listen.urn + "\n");
                Map<String, Long> names = spellings.get(artistKey);
                if (names == null) spellings.put(artistKey, names = new HashMap<>());
                Long named = names.get(name);
                names.put(name, (named == null ? 0 : named) + listen.playedMs);
            } else {
                result.unknownArtistPlays++;
            }
            int year = calendar.get(Calendar.YEAR);
            int month = calendar.get(Calendar.MONTH) + 1;
            int day = calendar.get(Calendar.DAY_OF_MONTH);
            long number = dayNumber(year, month, day);
            long[] slot = perDay.get(number);
            if (slot == null) perDay.put(number, slot = new long[]{0, year, month, day});
            slot[0] += listen.playedMs;
        }
        result.tracks = perTrack.size();
        result.artists = perArtist.size();
        result.years.addAll(descending(years));

        List<TrackPlays> tracks = new ArrayList<>();
        for (TrackPlays track : perTrack.values()) {
            if (track.plays > 0) tracks.add(track);
        }
        // The urn breaks a tie so the same history always gives the same top.
        Collections.sort(tracks, (a, b) -> a.plays != b.plays ? Integer.compare(b.plays, a.plays)
                : a.playedMs != b.playedMs ? Long.compare(b.playedMs, a.playedMs) : a.urn.compareTo(b.urn));
        for (int i = 0; i < Math.min(TOP, tracks.size()); i++) result.topTracks.add(tracks.get(i));

        List<ArtistTime> byArtist = new ArrayList<>();
        for (Map.Entry<String, Long> artist : perArtist.entrySet()) {
            byArtist.add(new ArtistTime(spelling(spellings.get(artist.getKey())), artist.getValue()));
        }
        Collections.sort(byArtist, (a, b) -> a.playedMs != b.playedMs ? Long.compare(b.playedMs, a.playedMs)
                : a.name.compareTo(b.name));
        for (int i = 0; i < Math.min(TOP, byArtist.size()); i++) result.topArtists.add(byArtist.get(i));
        // The new artists in the order of the time: the keys are looked up again by the name.
        Set<String> newKeys = new HashSet<>();
        for (String key : perArtist.keySet()) {
            if (!heardBefore.contains(key)) newKeys.add(key);
        }
        result.newArtists = newKeys.size();
        for (ArtistTime artist : byArtist) {
            if (result.newArtistNames.size() >= NEW_NAMES) break;
            if (newKeys.contains(keyOf(artist.name))) result.newArtistNames.add(artist.name);
        }

        long previous = 0;
        int run = 0;
        for (Map.Entry<Long, long[]> day : perDay.entrySet()) {
            long[] slot = day.getValue();
            if (slot[0] > result.busiestMs) {
                result.busiestMs = slot[0];
                result.busiestYear = (int) slot[1];
                result.busiestMonth = (int) slot[2];
                result.busiestDay = (int) slot[3];
            }
            run = run > 0 && day.getKey() == previous + 1 ? run + 1 : 1;
            previous = day.getKey();
            result.longestStreak = Math.max(result.longestStreak, run);
        }
        return result;
    }

    /** The first moment of the year and of the year after it, in the zone: [start, end). */
    public static long[] yearBounds(int year, TimeZone zone) {
        Calendar calendar = new GregorianCalendar(zone);
        calendar.clear();
        calendar.set(year, Calendar.JANUARY, 1, 0, 0, 0);
        long start = calendar.getTimeInMillis();
        calendar.clear();
        calendar.set(year + 1, Calendar.JANUARY, 1, 0, 0, 0);
        return new long[]{start, calendar.getTimeInMillis()};
    }

    /** One artist is written in different ways ("CUPSIZE", "Cupsize & ..."): the key is the same for all of them. */
    static String keyOf(String name) {
        String key = SongNames.artistKey(name);
        return key.isEmpty() ? name.trim().toLowerCase(java.util.Locale.ROOT) : key;
    }

    /** The key of the artist of the track, or null when it has none; the name as written is kept in {@code names}. */
    private static String artistKey(Map<String, String> names, String urn, Function<String, String> artistOf) {
        String known = names.get(urn);
        if (known == null) {
            String artist = artistOf == null ? null : artistOf.apply(urn);
            String trimmed = artist == null ? "" : artist.trim();
            known = trimmed.isEmpty() ? "" : keyOf(trimmed);
            names.put(urn, known);
            names.put(urn + "\n", trimmed);
        }
        return known.isEmpty() ? null : known;
    }

    private static String spelling(Map<String, Long> names) {
        String best = "";
        long longest = -1;
        for (Map.Entry<String, Long> name : names.entrySet()) {
            if (name.getValue() > longest || (name.getValue() == longest && name.getKey().compareTo(best) < 0)) {
                longest = name.getValue();
                best = name.getKey();
            }
        }
        return best;
    }

    private static List<Integer> descending(Set<Integer> years) {
        List<Integer> list = new ArrayList<>(years);
        Collections.reverse(list);
        return list;
    }

    /** Days since 1970-01-01 of a civil day, to find days in a row (the days_from_civil of Howard Hinnant). */
    static long dayNumber(int year, int month, int day) {
        long y = year - (month <= 2 ? 1 : 0);
        long era = (y >= 0 ? y : y - 399) / 400;
        long yearOfEra = y - era * 400;
        long monthPart = (month + 9) % 12;
        long dayOfYear = (153 * monthPart + 2) / 5 + day - 1;
        long dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
        return era * 146097 + dayOfEra - 719468;
    }
}
