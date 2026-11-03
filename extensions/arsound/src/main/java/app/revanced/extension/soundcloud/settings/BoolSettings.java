package app.revanced.extension.soundcloud.settings;

/**
 * Every on/off setting of the mod that lives in {@link Settings}' preferences file, with its default. The keys are
 * the ones users already have saved: they must not change.
 */
public final class BoolSettings {
    private BoolSettings() {
    }

    /** Off by default. If the context is not yet available, it stays off as well. */
    public static final BoolSetting TELEMETRY_ENABLED = new BoolSetting(Settings.TELEMETRY_ENABLED, false);
    /** On by default, the offer screen cannot load its prices outside of Google Play anyway. */
    public static final BoolSetting HIDE_SUBSCRIPTION_OFFERS = new BoolSetting(Settings.HIDE_SUBSCRIPTION_OFFERS, true);
    /**
     * Removes the Upgrade tab from the bottom bar. Off by default. Written in the background like most settings: the
     * Upgrade tab page only makes the main screen again, which reads the value from memory, and the "Bottom bar"
     * screen flushes it to the disk before its restart.
     */
    public static final BoolSetting HIDE_UPGRADE_TAB = new BoolSetting(Settings.HIDE_UPGRADE_TAB, false);
    /** Takes the Home, Feed and Search tabs out of the bottom bar (Library stays). Off by default; written at once: the app restarts. */
    public static final BoolSetting HIDE_TAB_HOME = new BoolSetting(Settings.HIDE_TAB_HOME, false, true);
    public static final BoolSetting HIDE_TAB_FEED = new BoolSetting(Settings.HIDE_TAB_FEED, false, true);
    public static final BoolSetting HIDE_TAB_SEARCH = new BoolSetting(Settings.HIDE_TAB_SEARCH, false, true);
    /** Takes the "My Wave" button out of the top bar of the Library. Off by default. */
    public static final BoolSetting HIDE_WAVE_BUTTON = new BoolSetting(Settings.HIDE_WAVE_BUTTON, false);
    /** Checks GitHub for a newer Arsound release on every launch. On by default. */
    public static final BoolSetting UPDATE_CHECK = new BoolSetting(Settings.UPDATE_CHECK, true);
    /** Shows stored playlists before the server answers. On by default. */
    public static final BoolSetting OFFLINE_FIRST = new BoolSetting(Settings.OFFLINE_FIRST, true);
    /** Retries transient playback errors automatically. On by default. */
    public static final BoolSetting PLAYBACK_RETRY = new BoolSetting(Settings.PLAYBACK_RETRY, true);
    /** Reduces background polling. On by default. */
    public static final BoolSetting POWER_SAVING = new BoolSetting(Settings.POWER_SAVING, true);
    public static final BoolSetting CUSTOM_DNS = new BoolSetting(Settings.CUSTOM_DNS, false);
    /** Saves the contents of all library playlists ahead of time, as text. On by default. */
    public static final BoolSetting PLAYLIST_PRELOAD = new BoolSetting(Settings.PLAYLIST_PRELOAD, true);
    /** Status pill on the main screen when there is no network or SoundCloud is switched off. On by default. */
    public static final BoolSetting NETWORK_BANNER = new BoolSetting(Settings.NETWORK_BANNER, true);
    /** Asks before SoundCloud's bot protection opens its check screen. On by default. */
    public static final BoolSetting DATADOME_PROMPT = new BoolSetting(Settings.DATADOME_PROMPT, true);
    /** Manual order of the library playlists, rearranged with a long press. On by default. */
    public static final BoolSetting PLAYLIST_ORDER = new BoolSetting(Settings.PLAYLIST_ORDER, true);
    /** The "Imported" playlist. On by default. */
    public static final BoolSetting SAVED_PLAYLIST = new BoolSetting(Settings.SAVED_PLAYLIST, true);
    public static final BoolSetting SAVED_PLAYLIST_HIDDEN = new BoolSetting(Settings.SAVED_PLAYLIST_HIDDEN, false);
    /** Imported files among "Your likes", on this phone only. On by default; independent of the "Imported" playlist. */
    public static final BoolSetting IMPORTED_IN_LIKES = new BoolSetting(Settings.IMPORTED_IN_LIKES, true);
    /** A file that is imported is liked at once: heart filled, among "Your likes". On by default; files already imported are not touched. */
    public static final BoolSetting LIKE_ON_IMPORT = new BoolSetting(Settings.LIKE_ON_IMPORT, true);
    public static final BoolSetting REGION_GUARD = new BoolSetting(Settings.REGION_GUARD, false);
    /** Checks the IP country again when the network or the VPN changes. On by default. */
    public static final BoolSetting REGION_RECHECK_ON_CHANGE = new BoolSetting(Settings.REGION_RECHECK_ON_CHANGE, true);
    /** Checks the IP country again every 30 seconds while requests go. On by default. */
    public static final BoolSetting REGION_RECHECK_PERIODIC = new BoolSetting(Settings.REGION_RECHECK_PERIODIC, true);
    /** The "?" next to "Not available in your country" on tracks, with its explanation. On by default. */
    public static final BoolSetting REGION_HINT = new BoolSetting(Settings.REGION_HINT, true);
    /** Plays without taking the audio focus, so other apps do not pause SoundCloud. Off by default. */
    public static final BoolSetting MIX_WITH_OTHER_APPS = new BoolSetting(Settings.MIX_WITH_OTHER_APPS, false);
    /** SoundCloud's own download buttons download with Arsound instead of offering a subscription. On by default. */
    public static final BoolSetting INTERCEPT_DOWNLOAD_BUTTONS = new BoolSetting(Settings.INTERCEPT_DOWNLOAD_BUTTONS, true);
    /** The lyrics button in the full-screen player. On by default; the lyrics themselves are shown after a tap on it. */
    public static final BoolSetting LYRICS_ENABLED = new BoolSetting(Settings.LYRICS_ENABLED, true);
    /** Off until the user taps the lyrics button the first time; nothing is looked up while it is off. */
    public static final BoolSetting LYRICS_VISIBLE = new BoolSetting(Settings.LYRICS_VISIBLE, false);
    /** Recognises the words of songs without lyrics with a neural network on the phone. Off until the user agrees. */
    public static final BoolSetting LYRICS_AI_ENABLED = new BoolSetting(Settings.LYRICS_AI_ENABLED, false);
    /** Takes the voice out of the music before the network listens: far fewer wrong words, several times slower. On by default. */
    public static final BoolSetting LYRICS_AI_SEPARATE = new BoolSetting(Settings.LYRICS_AI_SEPARATE, true);
    /** SoundCloud's "Share screenshot" sheet after a screenshot. Off by default. */
    public static final BoolSetting SCREENSHOT_SHARE = new BoolSetting(Settings.SCREENSHOT_SHARE, false);
    /** The animated background of the chosen theme: sparks, stars, fireflies, petals. On by default. */
    public static final BoolSetting THEME_EFFECTS = new BoolSetting(Settings.THEME_EFFECTS, true);
    /** Removes the picture behind the title of "Your likes" (the hearts) and of the purchases page. Off by default. */
    public static final BoolSetting HIDE_HEADER_ARTWORK = new BoolSetting(Settings.HIDE_HEADER_ARTWORK, false);
    /** Removes the theme's greeting ("Привет, имя / Что послушаем?") from the top of the home screen. Off by default. */
    public static final BoolSetting HIDE_HOME_GREETING = new BoolSetting(Settings.HIDE_HOME_GREETING, false);
    /**
     * Keeps the player from doing needless work while it is pulled up and down, so it does not stutter on the way
     * down. Off puts SoundCloud's own behaviour back. On by default.
     */
    public static final BoolSetting SMOOTH_PLAYER_COLLAPSE = new BoolSetting(Settings.SMOOTH_PLAYER_COLLAPSE, true);
    /** The end of a track fades out and the next one fades in. Off by default. */
    public static final BoolSetting CROSSFADE = new BoolSetting(Settings.CROSSFADE, false);
    /** Every track plays slowed, unless its title says it is slowed or sped up already. Off by default. */
    public static final BoolSetting SLOWED_PLAYBACK = new BoolSetting(Settings.SLOWED_PLAYBACK, false);
    /** Hides the comments that pop up over the player and their marks on the waveform. Off by default. */
    public static final BoolSetting HIDE_PLAYER_COMMENTS = new BoolSetting(Settings.HIDE_PLAYER_COMMENTS, false);
    public static final BoolSetting DUPLICATE_FILTER = new BoolSetting(Settings.DUPLICATE_FILTER, false);
    public static final BoolSetting MERGE_EDITED_VERSIONS = new BoolSetting(Settings.MERGE_EDITED_VERSIONS, false);
    public static final BoolSetting DEVELOPER_MODE = new BoolSetting(Settings.DEVELOPER_MODE, false);
    /**
     * Controls the player-level ad request guard. Enabled by default because an ad request that
     * cannot be fulfilled otherwise leaves the player in an unnecessary loading state.
     */
    public static final BoolSetting BLOCK_PLAYBACK_ADS = new BoolSetting(Settings.BLOCK_PLAYBACK_ADS, true);
    /**
     * Removes the playlist import banner ("Transfer your gems") from the library. On by default:
     * the banner comes back on its own after it is closed.
     */
    public static final BoolSetting HIDE_IMPORT_BANNER = new BoolSetting(Settings.HIDE_IMPORT_BANNER, true);
}
