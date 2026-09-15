package app.revanced.extension.soundcloud.recommendations;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Hides re-uploads of the same song in recommendations.
 * <p>
 * Two tracks are the same song when their normalized titles match and their durations differ
 * by at most two seconds. The first copy SoundCloud ranked highest is kept. Remixes, slowed and
 * sped up versions keep their marker in the title, so they stay separate unless the user chose
 * to merge them. Likes, playlists and profiles are not filtered.
 */
@SuppressWarnings("unused")
public final class DuplicateFilter {
    private static final long DURATION_TOLERANCE_MS = 2_000;
    private static final int AUTOPLAY_MEMORY = 300;

    private static final Pattern BRACKETS = Pattern.compile("[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]");
    private static final Pattern FEATURING = Pattern.compile("\\b(feat|ft|prod|featuring)\\b\\.?.*$");
    private static final Pattern NOISE = Pattern.compile("\\b(free download|free dl|official audio|official video|lyrics|audio|hq|hd|clean|explicit)\\b");
    private static final Pattern EDIT_MARKERS = Pattern.compile("\\b(slowed|reverb|sped up|speed up|nightcore|remix|edit|cover|live|instrumental|acapella|bass boosted)\\b");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Songs recently queued by autoplay, so a later refill does not bring back another copy. */
    private static final Map<String, Long> autoplayMemory = new LinkedHashMap<String, Long>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > AUTOPLAY_MEMORY;
        }
    };

    /** Track urn to its song key and duration, learned from filtered lists. */
    private static final Map<String, Object[]> knownTracks = new LinkedHashMap<String, Object[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
            return size() > 2000;
        }
    };

    private DuplicateFilter() {
    }

    static String songKey(String title) {
        if (title == null) return "";
        String value = title.toLowerCase(Locale.ROOT);
        // Markers of a different version are kept apart from the brackets they are usually written in.
        StringBuilder markers = new StringBuilder();
        if (!Settings.isMergeEditedVersions()) {
            java.util.regex.Matcher matcher = EDIT_MARKERS.matcher(value);
            while (matcher.find()) markers.append(' ').append(matcher.group(1));
        }
        value = BRACKETS.matcher(value).replaceAll(" ");
        value = FEATURING.matcher(value).replaceAll(" ");
        value = NOISE.matcher(value).replaceAll(" ");
        if (!Settings.isMergeEditedVersions()) value = EDIT_MARKERS.matcher(value).replaceAll(" ");
        // "Artist - Title" uploads: the part after the dash is the title in most re-uploads.
        int dash = value.lastIndexOf(" - ");
        if (dash > 0 && dash < value.length() - 3) value = value.substring(dash + 3);
        value = NON_WORD.matcher(value).replaceAll(" ").trim();
        return value + markers;
    }

    private static final class Song {
        final String key;
        final long duration;

        Song(String key, long duration) {
            this.key = key;
            this.duration = duration;
        }

        boolean sameAs(Song other) {
            return !key.isEmpty() && key.equals(other.key) && Math.abs(duration - other.duration) <= DURATION_TOLERANCE_MS;
        }
    }

    private static Song songOf(Object track) {
        try {
            String title = (String) track.getClass().getMethod("getTitle").invoke(track);
            long duration = (long) track.getClass().getMethod("getFullDuration").invoke(track);
            Song song = new Song(songKey(title), duration);
            Object urn = track.getClass().getMethod("getUrn").invoke(track);
            synchronized (knownTracks) {
                knownTracks.put(String.valueOf(urn), new Object[]{song.key, song.duration});
            }
            return song;
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean containsSame(List<Song> songs, Song song) {
        for (Song kept : songs) if (kept.sameAs(song)) return true;
        return false;
    }

    /**
     * Home screen sections. Entities that are not tracks are kept as they are.
     *
     * @param entities {@code List<SectionEntity>}.
     */
    public static List<?> filterSectionEntities(List<?> entities) {
        if (!Settings.isDuplicateFilterEnabled() || entities == null) return entities;
        try {
            List<Object> result = new ArrayList<>(entities.size());
            List<Song> kept = new ArrayList<>();
            int removed = 0;
            for (Object entity : entities) {
                Object track = trackItemOf(entity);
                Song song = track == null ? null : songOf(track);
                if (song != null && containsSame(kept, song)) {
                    removed++;
                    continue;
                }
                if (song != null) kept.add(song);
                result.add(entity);
            }
            int count = removed, total = entities.size();
            Logger.printInfo(() -> "Home section checked: " + total + " items, duplicates hidden: " + count);
            return result;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not filter section duplicates", ex);
            return entities;
        }
    }

    private static Object trackItemOf(Object entity) throws IllegalAccessException {
        if (entity == null || !entity.getClass().getName().endsWith("SectionTrackEntity")) return null;
        for (Field field : entity.getClass().getDeclaredFields()) {
            if (field.getType().getName().endsWith(".TrackItem")) {
                field.setAccessible(true);
                return field.get(entity);
            }
        }
        return null;
    }

    /**
     * Autoplay: recommendations appended after the current track.
     *
     * @param apiTracks {@code Iterable<ApiTrack>}.
     * @param seedUrn   The track the recommendations are for.
     */
    public static Iterator<?> filterAutoplay(Iterable<?> apiTracks, Object seedUrn) {
        if (!Settings.isDuplicateFilterEnabled()) return apiTracks.iterator();
        List<Object> result = new ArrayList<>();
        try {
            List<Song> kept = new ArrayList<>();
            Object[] seed;
            synchronized (knownTracks) {
                seed = knownTracks.get(String.valueOf(seedUrn).replace("soundcloud:sounds:", "soundcloud:tracks:"));
            }
            if (seed != null) kept.add(new Song((String) seed[0], (long) seed[1]));

            int removed = 0;
            for (Object track : apiTracks) {
                Song song = songOf(track);
                boolean duplicate = song != null && (containsSame(kept, song) || recentlyQueued(song));
                if (duplicate) {
                    removed++;
                    continue;
                }
                if (song != null) {
                    kept.add(song);
                    rememberQueued(song);
                }
                result.add(track);
            }
            int count = removed;
            Logger.printInfo(() -> "Autoplay checked, duplicates hidden: " + count);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not filter autoplay duplicates", ex);
            return apiTracks.iterator();
        }
        return result.iterator();
    }

    private static boolean recentlyQueued(Song song) {
        synchronized (autoplayMemory) {
            Long duration = autoplayMemory.get(song.key);
            return duration != null && !song.key.isEmpty() && Math.abs(duration - song.duration) <= DURATION_TOLERANCE_MS;
        }
    }

    private static void rememberQueued(Song song) {
        synchronized (autoplayMemory) {
            autoplayMemory.put(song.key, song.duration);
        }
    }

    /** Remembers the song of a track item the user plays or sees, so autoplay can skip its copies. */
    public static void learn(Object trackItem) {
        if (trackItem != null && Settings.isDuplicateFilterEnabled()) songOf(trackItem);
    }
}
