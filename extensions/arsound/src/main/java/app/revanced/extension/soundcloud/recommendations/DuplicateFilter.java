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
    private static final Pattern EDIT_MARKERS = Pattern.compile("\\b(slowed|slow|reverb|sped up|speed up|speed|nightcore|remix|edit|cover|live|instrumental|acapella|bass boosted|pitched|8d)\\b");
    /** A playback speed written as a number: "0.9x", "x1.25", "1,1 speed". */
    private static final Pattern SPEED = Pattern.compile("(\\b\\d(?:[.,]\\d+)?\\s*x\\b|\\bx\\s*\\d(?:[.,]\\d+)?\\b|\\b\\d(?:[.,]\\d+)?\\s*(?=speed\\b))");
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
            if (SPEED.matcher(value).find()) markers.append(" speed");
        }
        value = SPEED.matcher(value).replaceAll(" ");
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
        /** Slowed, sped up, remixed and similar: another speed changes the duration. */
        final boolean edited;

        Song(String key, long duration, boolean edited) {
            this.key = key;
            this.duration = duration;
            this.edited = edited;
        }

        boolean sameAs(Song other) {
            if (key.isEmpty() || !key.equals(other.key)) return false;
            // Merged versions have the same key; their durations differ with the speed.
            if ((edited || other.edited) && Settings.isMergeEditedVersions()) return true;
            return Math.abs(duration - other.duration) <= DURATION_TOLERANCE_MS;
        }
    }

    static boolean isEdited(String title) {
        String value = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return EDIT_MARKERS.matcher(value).find() || SPEED.matcher(value).find();
    }

    private static Song song(String title, long duration) {
        return new Song(songKey(title), duration, isEdited(title));
    }

    private static Object urnOf(Object track) {
        try {
            return track.getClass().getMethod("getUrn").invoke(track);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Song songOf(Object track) {
        try {
            String title = (String) track.getClass().getMethod("getTitle").invoke(track);
            long duration = (long) track.getClass().getMethod("getFullDuration").invoke(track);
            Song song = song(title, duration);
            Object urn = track.getClass().getMethod("getUrn").invoke(track);
            synchronized (knownTracks) {
                knownTracks.put(String.valueOf(urn), new Object[]{title, song.duration});
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
        boolean duplicates = Settings.isDuplicateFilterEnabled();
        if (!duplicates && !TrackDislikes.isActive() || entities == null) return entities;
        try {
            List<Object> result = new ArrayList<>(entities.size());
            List<Song> kept = new ArrayList<>();
            int removed = 0;
            for (Object entity : entities) {
                Object track = trackItemOf(entity);
                if (track != null && TrackDislikes.isDisliked(urnOf(track))) {
                    removed++;
                    continue;
                }
                Song song = track == null || !duplicates ? null : songOf(track);
                if (song != null && containsSame(kept, song)) {
                    removed++;
                    continue;
                }
                if (song != null) kept.add(song);
                result.add(entity);
            }
            int count = removed, total = entities.size();
            Logger.printInfo(() -> "Home section checked: " + total + " items, hidden: " + count);
            return result;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not filter section duplicates", ex);
            return entities;
        }
    }

    /**
     * Injection point. Items of a server-driven home screen block ({@code ArrayList<SDUIView>}),
     * called from the constructors of carousel, gallery and suggestion views. Filters the list in place.
     */
    public static void filterHomeViews(java.util.ArrayList<Object> views) {
        if (!Settings.isDuplicateFilterEnabled() && !TrackDislikes.isActive() || views == null) return;
        List<?> filtered = filterSectionEntities(views);
        if (filtered.size() == views.size()) return;
        views.clear();
        views.addAll(filtered);
    }

    private static Object trackItemOf(Object entity) throws IllegalAccessException {
        if (entity == null) return null;
        String name = entity.getClass().getName();
        if (!name.endsWith("SectionTrackEntity") && !name.endsWith("SDUIView$Track") && !name.endsWith("SDUIView$Repost$Track")) return null;
        for (Field field : entity.getClass().getDeclaredFields()) {
            String type = field.getType().getName();
            if (type.endsWith(".TrackItem")) {
                field.setAccessible(true);
                return field.get(entity);
            }
            // A repost holds the reposted track view.
            if (type.endsWith("SDUIView$Track")) {
                field.setAccessible(true);
                return trackItemOf(field.get(entity));
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
        boolean duplicates = Settings.isDuplicateFilterEnabled();
        if (!duplicates && !TrackDislikes.isActive()) return apiTracks.iterator();
        List<Object> result = new ArrayList<>();
        try {
            List<Song> kept = new ArrayList<>();
            Object[] seed;
            synchronized (knownTracks) {
                seed = knownTracks.get(String.valueOf(seedUrn).replace("soundcloud:sounds:", "soundcloud:tracks:"));
            }
            if (seed != null) kept.add(song((String) seed[0], (long) seed[1]));

            int removed = 0;
            for (Object track : apiTracks) {
                if (TrackDislikes.isDisliked(urnOf(track))) {
                    removed++;
                    continue;
                }
                Song song = duplicates ? songOf(track) : null;
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
            Logger.printInfo(() -> "Autoplay checked, hidden: " + count);
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

    /**
     * Playlists SoundCloud makes for the user (Your Mix, Daily Drops, Weekly Wave): duplicates and
     * disliked tracks are taken out of the track list. Own and other people's playlists are not touched.
     * Blocks: titles unknown so far are asked from SoundCloud, one request per 50 tracks.
     *
     * @param urns The track urns of the playlist, changed in place.
     */
    public static void filterSystemPlaylist(String playlistUrn, List<Object> urns) {
        boolean duplicates = Settings.isDuplicateFilterEnabled();
        if (!playlistUrn.contains(":system-playlists:") || !duplicates && !TrackDislikes.isActive()) return;
        try {
            if (duplicates) learnTitles(urns);
            List<Song> kept = new ArrayList<>();
            int before = urns.size();
            for (java.util.Iterator<Object> iterator = urns.iterator(); iterator.hasNext(); ) {
                String urn = String.valueOf(iterator.next());
                if (TrackDislikes.isDisliked(urn)) {
                    iterator.remove();
                    continue;
                }
                if (!duplicates) continue;
                Object[] known;
                synchronized (knownTracks) {
                    known = knownTracks.get(urn);
                }
                if (known == null) continue;
                Song song = song((String) known[0], (long) known[1]);
                if (containsSame(kept, song)) {
                    iterator.remove();
                } else {
                    kept.add(song);
                }
            }
            int removed = before - urns.size();
            Logger.printInfo(() -> "System playlist " + playlistUrn + " checked: " + before + " tracks, hidden: " + removed);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not filter system playlist " + playlistUrn, ex);
        }
    }

    private static void learnTitles(List<Object> urns) {
        List<String> missing = new ArrayList<>();
        synchronized (knownTracks) {
            for (Object urn : urns) {
                String value = String.valueOf(urn);
                if (!knownTracks.containsKey(value) && value.startsWith("soundcloud:tracks:")) {
                    missing.add(value.substring("soundcloud:tracks:".length()));
                }
            }
        }
        for (int start = 0; start < missing.size(); start += 50) {
            try {
                String[] response = app.revanced.extension.soundcloud.download.DownloadTrackPatch.apiGet(
                        "https://api-v2.soundcloud.com/tracks?ids=" + String.join(",", missing.subList(start, Math.min(missing.size(), start + 50))));
                if (response[1] == null) return;
                org.json.JSONArray array = new org.json.JSONArray(response[1]);
                synchronized (knownTracks) {
                    for (int i = 0; i < array.length(); i++) {
                        org.json.JSONObject track = array.getJSONObject(i);
                        knownTracks.put("soundcloud:tracks:" + track.getLong("id"),
                                new Object[]{track.optString("title"), track.optLong("full_duration", track.optLong("duration"))});
                    }
                }
            } catch (Exception ex) {
                // Offline: the playlist is shown as it is.
                Logger.printInfo(() -> "No titles for the system playlist check: " + ex);
                return;
            }
        }
    }

    /** Remembers the song of a track item the user plays or sees, so autoplay can skip its copies. */
    public static void learn(Object trackItem) {
        if (trackItem != null && Settings.isDuplicateFilterEnabled()) songOf(trackItem);
    }
}
