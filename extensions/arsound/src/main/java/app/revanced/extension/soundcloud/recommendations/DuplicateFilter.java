package app.revanced.extension.soundcloud.recommendations;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Hides re-uploads of the same song in recommendations.
 * <p>
 * Two tracks are the same song when their normalized titles match and their durations differ
 * by at most two seconds ({@link SongKeys}, {@link SongFingerprint}). The first copy SoundCloud ranked highest is
 * kept. Remixes, slowed and sped up versions keep their marker in the title, so they stay separate unless the user
 * chose to merge them. Likes, playlists and profiles are not filtered.
 * <p>
 * This class holds the injection points; each list is walked by a {@link DuplicatePass}.
 */
@SuppressWarnings("unused")
public final class DuplicateFilter {
    private static final String SYSTEM_PLAYLIST_MARK = ":system-playlists:";

    private DuplicateFilter() {
    }

    /** Whether any filter has something to do: duplicates are hidden, or tracks are disliked. */
    private static boolean isActive(boolean duplicates) {
        return duplicates || TrackDislikes.isActive();
    }

    /** The song of a track item, compared the way the user chose for the lists. */
    private static SongFingerprint fingerprintOf(Object track) {
        SongRef song = TrackItems.songOf(track);
        return song == null ? null : song.fingerprint(Settings.isMergeEditedVersions());
    }

    /**
     * Home screen sections. Entities that are not tracks are kept as they are.
     *
     * @param entities {@code List<SectionEntity>}.
     */
    public static List<?> filterSectionEntities(List<?> entities) {
        boolean duplicates = Settings.isDuplicateFilterEnabled();
        if (entities == null || !isActive(duplicates)) return entities;
        try {
            List<Object> result = new ArrayList<>(entities.size());
            DuplicatePass pass = new DuplicatePass();
            for (Object entity : entities) {
                Object track = TrackItems.trackItemOf(entity);
                if (track != null && pass.dislikes(TrackItems.urnOf(track))) continue;
                SongFingerprint song = track == null || !duplicates ? null : fingerprintOf(track);
                if (pass.keeps(song)) result.add(entity);
            }
            int removed = pass.removed(), total = entities.size();
            Logger.printInfo(() -> "Home section checked: " + total + " items, hidden: " + removed);
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
    public static void filterHomeViews(ArrayList<Object> views) {
        if (views == null) return;
        List<?> filtered = filterSectionEntities(views);
        if (filtered.size() == views.size()) return;
        views.clear();
        views.addAll(filtered);
    }

    /**
     * Autoplay: recommendations appended after the current track.
     *
     * @param apiTracks {@code Iterable<ApiTrack>}.
     * @param seedUrn   The track the recommendations are for.
     */
    public static Iterator<?> filterAutoplay(Iterable<?> apiTracks, Object seedUrn) {
        boolean duplicates = Settings.isDuplicateFilterEnabled();
        if (!isActive(duplicates)) return apiTracks.iterator();
        List<Object> result = new ArrayList<>();
        try {
            DuplicatePass pass = new DuplicatePass.Autoplay();
            SongRef seed = KnownTracks.get(TrackUrn.normalize(seedUrn));
            if (seed != null) pass.keep(seed.fingerprint(Settings.isMergeEditedVersions()));

            for (Object track : apiTracks) {
                if (pass.dislikes(TrackItems.urnOf(track))) continue;
                SongFingerprint song = duplicates ? fingerprintOf(track) : null;
                if (pass.keeps(song)) result.add(track);
            }
            int removed = pass.removed();
            Logger.printInfo(() -> "Autoplay checked, hidden: " + removed);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not filter autoplay duplicates", ex);
            return apiTracks.iterator();
        }
        return result.iterator();
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
        if (!playlistUrn.contains(SYSTEM_PLAYLIST_MARK) || !isActive(duplicates)) return;
        try {
            if (duplicates) SystemPlaylistTitles.learn(urns);
            DuplicatePass pass = new DuplicatePass();
            int before = urns.size();
            for (Iterator<Object> iterator = urns.iterator(); iterator.hasNext(); ) {
                String urn = String.valueOf(iterator.next());
                if (pass.dislikes(urn)) {
                    iterator.remove();
                    continue;
                }
                SongRef known = duplicates ? KnownTracks.get(urn) : null;
                SongFingerprint song = known == null ? null : known.fingerprint(Settings.isMergeEditedVersions());
                if (!pass.keeps(song)) iterator.remove();
            }
            int removed = before - urns.size();
            Logger.printInfo(() -> "System playlist " + playlistUrn + " checked: " + before + " tracks, hidden: " + removed);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not filter system playlist " + playlistUrn, ex);
        }
    }
}
