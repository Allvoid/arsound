package app.revanced.extension.soundcloud.recommendations;

import java.lang.reflect.Field;

import app.revanced.extension.soundcloud.shared.Getters;

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
            // The getters are looked up once per class: the filter asks for every entry of every list.
            String title = (String) Getters.get(track, "getTitle");
            long duration = (long) Getters.get(track, "getFullDuration");
            SongRef song = new SongRef(title, duration);
            KnownTracks.remember(String.valueOf(Getters.get(track, "getUrn")), song);
            return song;
        } catch (Exception ex) {
            return null;
        }
    }

    /** The field of an entity class that holds its track, or {@link #NO_FIELD}; found once per class. */
    private static final java.util.Map<Class<?>, Field> TRACK_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Field NO_FIELD;

    static {
        Field none = null;
        try {
            none = TrackItems.class.getDeclaredField("NO_FIELD");
        } catch (NoSuchFieldException ignored) {
            // Cannot happen: the field is right here.
        }
        NO_FIELD = none;
    }

    /** The {@code TrackItem} inside a home section entity, or null for an entity that is not a track. */
    static Object trackItemOf(Object entity) throws IllegalAccessException {
        if (entity == null) return null;
        String name = entity.getClass().getName();
        if (!name.endsWith("SectionTrackEntity") && !name.endsWith("SDUIView$Track") && !name.endsWith("SDUIView$Repost$Track")) return null;
        Field field = TRACK_FIELDS.get(entity.getClass());
        if (field == null) {
            field = NO_FIELD;
            for (Field candidate : entity.getClass().getDeclaredFields()) {
                String type = candidate.getType().getName();
                if (type.endsWith(".TrackItem") || type.endsWith("SDUIView$Track")) {
                    candidate.setAccessible(true);
                    field = candidate;
                    break;
                }
            }
            TRACK_FIELDS.put(entity.getClass(), field);
        }
        if (field == NO_FIELD || field == null) return null;
        Object value = field.get(entity);
        // A repost holds the reposted track view.
        return field.getType().getName().endsWith("SDUIView$Track") ? trackItemOf(value) : value;
    }
}
