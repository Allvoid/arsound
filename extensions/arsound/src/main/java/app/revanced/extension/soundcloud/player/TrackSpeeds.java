package app.revanced.extension.soundcloud.player;

import app.revanced.extension.soundcloud.settings.Settings;

/**
 * A speed of its own for a single track: "this one always plays slowed" (or sped up, or at its normal speed while
 * everything else is slowed). It is set in the menu of the track ({@link TrackSpeedPanel}) and used whenever the track
 * starts, wherever it plays ({@link SlowedPlayback}). How slow "slowed" is and how fast "sped up" is stays the one
 * choice of the versions window ({@link TrackVersions}).
 * <p>
 * Kept in the app's settings as one text ({@link TrackSpeedList}), so it travels with the backup of the settings.
 */
public final class TrackSpeeds {
    /** The setting that holds the list. */
    public static final String KEY = "track_speeds";

    private static final Object LOCK = new Object();
    /** The list, read once. */
    private static TrackSpeedList list;
    /** The text the list was read from, to notice that the settings were imported or reset meanwhile. */
    private static String readFrom;

    private TrackSpeeds() {
    }

    private static final String FILE_PREFIX = "file:";

    /**
     * What the list knows a track by, or null for something that is not a track (an ad, a playlist). A SoundCloud
     * track: its urn ("sounds" in some places and "tracks" in others; kept as "tracks"). An imported file: its path,
     * as the menu and the player name the same file by different urns.
     */
    static String keyOf(Object urn) {
        if (urn == null) return null;
        try {
            java.io.File imported = app.revanced.extension.soundcloud.local.LocalAdditions.importedFileOf(urn);
            if (imported != null) return FILE_PREFIX + imported.getPath();
        } catch (Exception ex) {
            return null;
        }
        String text = String.valueOf(urn).replace("soundcloud:sounds:", "soundcloud:tracks:");
        return text.contains(":tracks:") ? text : null;
    }

    private static TrackSpeedList list() {
        String stored = Settings.getString(KEY, "");
        // Compared by identity first: the settings hand out the same text until it changes.
        if (list == null || (stored != readFrom && !stored.equals(readFrom))) {
            list = TrackSpeedList.parse(stored);
            readFrom = stored;
        }
        return list;
    }

    /** The speed this track always plays at, or null if it follows the common choice. Any thread. */
    public static VersionSpeed of(Object urn) {
        String key = keyOf(urn);
        if (key == null) return null;
        synchronized (LOCK) {
            return list().get(key);
        }
    }

    /** Sets the speed of the track; null gives it back to the common choice. The playing track follows at once. */
    public static void set(Object urn, VersionSpeed speed) {
        String key = keyOf(urn);
        if (key == null) return;
        synchronized (LOCK) {
            TrackSpeedList speeds = list();
            speeds.put(key, speed);
            String text = speeds.format();
            Settings.putString(KEY, text);
            readFrom = text;
        }
        SlowedPlayback.onSettingChanged();
    }
}
