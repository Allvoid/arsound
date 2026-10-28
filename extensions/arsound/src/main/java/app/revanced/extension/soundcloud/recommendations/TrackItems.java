package app.revanced.extension.soundcloud.recommendations;

import java.lang.reflect.Field;

/** Reads SoundCloud's track objects, whose classes are obfuscated, by the getters and field types that survive. */
final class TrackItems {
    private TrackItems() {
    }

    /** The urn of a {@code TrackItem} or {@code ApiTrack}, or null. */
    static Object urnOf(Object track) {
        try {
            return track.getClass().getMethod("getUrn").invoke(track);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * The title and length of a track, remembered by its urn. Null if the object does not have them.
     */
    static SongRef songOf(Object track) {
        try {
            String title = (String) track.getClass().getMethod("getTitle").invoke(track);
            long duration = (long) track.getClass().getMethod("getFullDuration").invoke(track);
            SongRef song = new SongRef(title, duration);
            Object urn = track.getClass().getMethod("getUrn").invoke(track);
            KnownTracks.remember(String.valueOf(urn), song);
            return song;
        } catch (Exception ex) {
            return null;
        }
    }

    /** The {@code TrackItem} inside a home section entity, or null for an entity that is not a track. */
    static Object trackItemOf(Object entity) throws IllegalAccessException {
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
}
