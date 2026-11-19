package app.revanced.extension.soundcloud.player;

import app.revanced.extension.soundcloud.settings.Settings;

/**
 * A speed of its own for a single track: "this one always plays slowed" (or sped up, or at its normal speed while
 * everything else is slowed). It is set in the menu of the track ({@link TrackSpeedPanel}) and used whenever the track
 * starts, wherever it plays ({@link SlowedPlayback}). How slow "slowed" is and how fast "sped up" is stays the one
 * choice of the versions window ({@link TrackVersions}).
 * <p>
 * Kept in the app's settings as one text ({@link TrackSpeedList}), so it travels with the backup of the settings.
 * <p>
 * The same goes for the reverb and the bass boost of a track and their strengths ({@link TrackFxList}, the setting
 * {@link #FX_KEY}): each is "as for every track" (none of its own), off, weak, medium or strong, as on the desktop.
 */
public final class TrackSpeeds {
    /** The setting that holds the list. */
    public static final String KEY = "track_speeds";
    /** The setting that holds the list of the reverbs and bass boosts of single tracks. */
    public static final String FX_KEY = "track_fx";

    private static final Object LOCK = new Object();
    /** The list, read once. */
    private static TrackSpeedList list;
    /** The text the list was read from, to notice that the settings were imported or reset meanwhile. */
    private static String readFrom;
    private static TrackFxList fxList;
    private static String fxReadFrom;

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

    private static TrackFxList fxList() {
        String stored = Settings.getString(FX_KEY, "");
        if (fxList == null || (stored != fxReadFrom && !stored.equals(fxReadFrom))) {
            fxList = TrackFxList.parse(stored);
            fxReadFrom = stored;
        }
        return fxList;
    }

    /** The reverb and bass boost this track keeps of its own, or {@link TrackFxList.Own#NONE}. Any thread. */
    static TrackFxList.Own fxOf(Object urn) {
        String key = keyOf(urn);
        if (key == null) return TrackFxList.Own.NONE;
        synchronized (LOCK) {
            return fxList().get(key);
        }
    }

    /** The reverb the track plays with: its own, or the common choice. Any thread. */
    static VersionStrength reverbOf(Object urn) {
        return TrackFxList.effective(fxOf(urn).reverb, TrackVersions.reverb());
    }

    /** The bass boost the track plays with: its own, or the common choice. Any thread. */
    static VersionStrength bassOf(Object urn) {
        return TrackFxList.effective(fxOf(urn).bass, TrackVersions.bass());
    }

    /**
     * Sets the reverb and the bass boost of the track; null for either is "as for every track", and null for both
     * gives the track back to the common choice. The playing track follows at once.
     */
    static void setFx(Object urn, VersionStrength reverb, VersionStrength bass) {
        String key = keyOf(urn);
        if (key == null) return;
        synchronized (LOCK) {
            TrackFxList rows = fxList();
            rows.put(key, reverb, bass);
            String text = rows.format();
            Settings.putString(FX_KEY, text);
            fxReadFrom = text;
        }
        AudioEqualizer.onVersionsChanged();
    }
}
