package app.revanced.extension.soundcloud.settings;

import android.content.Context;
import android.content.SharedPreferences;

import app.revanced.extension.shared.Utils;

/**
 * Settings of the ReVanced SoundCloud patches, stored in a separate preferences file.
 */
@SuppressWarnings("unused")
public final class Settings {
    private static final String PREFERENCES_NAME = "revanced_soundcloud";

    public static final String TELEMETRY_ENABLED = "telemetry_enabled";

    /** The preferences file, or null while the app context is not available yet. */
    static SharedPreferences preferences() {
        Context context = Utils.getContext();
        if (context == null) return null;

        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isTelemetryEnabled() {
        return BoolSettings.TELEMETRY_ENABLED.get();
    }

    public static final String HIDE_SUBSCRIPTION_OFFERS = "hide_subscription_offers";

    public static boolean isHideSubscriptionOffersEnabled() {
        return BoolSettings.HIDE_SUBSCRIPTION_OFFERS.get();
    }

    public static void setHideSubscriptionOffersEnabled(boolean enabled) {
        BoolSettings.HIDE_SUBSCRIPTION_OFFERS.set(enabled);
    }

    public static final String HIDE_UPGRADE_TAB = "hide_upgrade_tab";

    /** Removes the Upgrade tab from the bottom bar. Off by default. */
    public static boolean isHideUpgradeTabEnabled() {
        return BoolSettings.HIDE_UPGRADE_TAB.get();
    }

    /** Written at once, not in the background: the Upgrade tab page restarts the app right after it. */
    public static void setHideUpgradeTabEnabled(boolean enabled) {
        BoolSettings.HIDE_UPGRADE_TAB.set(enabled);
    }

    /** Bottom bar tabs the user took out (Library is never one of them). Off by default; the app restarts to apply. */
    /** The "My Wave" card on the home screen. */
    public static final String WAVE_CARD = "wave_card";
    public static final String HIDE_TAB_HOME = "hide_tab_home";
    public static final String HIDE_TAB_FEED = "hide_tab_feed";
    public static final String HIDE_TAB_SEARCH = "hide_tab_search";

    public static final String UPDATE_CHECK = "update_check";

    /** Checks GitHub for a newer Arsound release on every launch. On by default. */
    public static boolean isUpdateCheckEnabled() {
        return BoolSettings.UPDATE_CHECK.get();
    }

    public static void setUpdateCheckEnabled(boolean enabled) {
        BoolSettings.UPDATE_CHECK.set(enabled);
    }

    public static final String OFFLINE_FIRST = "offline_first";

    /** Shows stored playlists before the server answers. On by default. */
    public static boolean isOfflineFirstEnabled() {
        return BoolSettings.OFFLINE_FIRST.get();
    }

    public static void setOfflineFirstEnabled(boolean enabled) {
        BoolSettings.OFFLINE_FIRST.set(enabled);
    }

    public static final String PLAYBACK_RETRY = "playback_retry";

    /** Retries transient playback errors automatically. On by default. */
    public static boolean isPlaybackRetryEnabled() {
        return BoolSettings.PLAYBACK_RETRY.get();
    }

    public static void setPlaybackRetryEnabled(boolean enabled) {
        BoolSettings.PLAYBACK_RETRY.set(enabled);
    }

    public static final String POWER_SAVING = "power_saving";

    /** Reduces background polling. On by default. */
    public static boolean isPowerSavingEnabled() {
        return BoolSettings.POWER_SAVING.get();
    }

    public static void setPowerSavingEnabled(boolean enabled) {
        BoolSettings.POWER_SAVING.set(enabled);
    }

    public static final String CUSTOM_DNS = "custom_dns";
    public static final String DNS_PRESET = "dns_preset";
    public static final String DNS_MODE = "dns_mode";
    public static final String CUSTOM_DOH_URL = "custom_doh_url";
    public static final String CUSTOM_DNS_SERVERS = "custom_dns_servers";

    public static boolean isCustomDnsEnabled() {
        return BoolSettings.CUSTOM_DNS.get();
    }

    public static String getDnsPreset() {
        SharedPreferences preferences = preferences();
        return preferences == null ? "xbox" : preferences.getString(DNS_PRESET, "xbox");
    }

    public static String getDnsMode() {
        SharedPreferences preferences = preferences();
        return preferences == null ? "auto" : preferences.getString(DNS_MODE, "auto");
    }

    public static String getCustomDohUrl() {
        SharedPreferences preferences = preferences();
        return preferences == null ? "" : preferences.getString(CUSTOM_DOH_URL, "");
    }

    public static String getCustomDnsServers() {
        SharedPreferences preferences = preferences();
        return preferences == null ? "" : preferences.getString(CUSTOM_DNS_SERVERS, "");
    }

    public static void putBoolean(String key, boolean value) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putBoolean(key, value).apply();
    }

    public static void putString(String key, String value) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putString(key, value).apply();
    }

    /**
     * Waits until every value put in the background is on the disk: a commit of an empty edit writes whatever is
     * pending. For the moment before the app ends its own process. Not on the main thread: it waits for the disk.
     */
    public static void flush() {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().commit();
    }

    /** Written to the disk before it returns, for a value the app restarts right after. */
    public static void putStringNow(String key, String value) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putString(key, value).commit();
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        SharedPreferences preferences = preferences();
        return preferences == null ? defaultValue : preferences.getBoolean(key, defaultValue);
    }

    public static String getString(String key, String defaultValue) {
        SharedPreferences preferences = preferences();
        return preferences == null ? defaultValue : preferences.getString(key, defaultValue);
    }

    public static long getLong(String key, long defaultValue) {
        SharedPreferences preferences = preferences();
        return preferences == null ? defaultValue : preferences.getLong(key, defaultValue);
    }

    public static void putLong(String key, long value) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putLong(key, value).apply();
    }

    /** Size of SoundCloud's stream cache in bytes; 0 keeps SoundCloud's own size. */
    public static final String STREAM_CACHE_SIZE = "stream_cache_size";
    /** Days a cached stream part is kept after its last use; 0 keeps it until the cache is full. */
    public static final String STREAM_CACHE_DAYS = "stream_cache_days";

    public static final String PLAYLIST_PRELOAD = "playlist_preload";

    /** Saves the contents of all library playlists ahead of time, as text. On by default. */
    public static boolean isPlaylistPreloadEnabled() {
        return BoolSettings.PLAYLIST_PRELOAD.get();
    }

    public static final String NETWORK_BANNER = "network_banner";

    /** Status pill on the main screen when there is no network or SoundCloud is switched off. On by default. */
    public static boolean isNetworkBannerEnabled() {
        return BoolSettings.NETWORK_BANNER.get();
    }

    public static final String DATADOME_PROMPT = "datadome_prompt";

    /** Asks before SoundCloud's bot protection opens its check screen. On by default. */
    public static boolean isDataDomePromptEnabled() {
        return BoolSettings.DATADOME_PROMPT.get();
    }

    public static final String PLAYLIST_ORDER = "playlist_order";

    /** Manual order of the library playlists, rearranged with a long press. On by default. */
    public static boolean isPlaylistOrderEnabled() {
        return BoolSettings.PLAYLIST_ORDER.get();
    }

    public static final String SAVED_PLAYLIST = "saved_playlist";
    public static final String SAVED_PLAYLIST_HIDDEN = "saved_playlist_hidden";

    /** The "Imported" playlist. On by default. */
    public static boolean isSavedPlaylistEnabled() {
        return BoolSettings.SAVED_PLAYLIST.get();
    }

    public static boolean isSavedPlaylistHidden() {
        return BoolSettings.SAVED_PLAYLIST_HIDDEN.get();
    }

    public static final String IMPORTED_IN_LIKES = "imported_in_likes";
    /** Whether a file is liked by itself when it is imported. */
    public static final String LIKE_ON_IMPORT = "like_on_import";

    /** Imported files among "Your likes", on this phone only. On by default; independent of the "Imported" playlist. */
    public static boolean isImportedInLikesEnabled() {
        return BoolSettings.IMPORTED_IN_LIKES.get();
    }

    public static final String REGION_GUARD = "region_guard";

    public static boolean isRegionGuardEnabled() {
        return BoolSettings.REGION_GUARD.get();
    }

    public static final String REGION_GUARD_COUNTRIES = "region_guard_countries";
    /** The countries that were blocked before the choice existed. */
    private static final String DEFAULT_GUARD_COUNTRIES = "RU";

    /** The stored text and the set parsed from it, swapped together so a reader never sees one without the other. */
    private static final class GuardCountries {
        final String text;
        final java.util.Set<String> codes;

        GuardCountries(String text) {
            java.util.Set<String> parsed = new java.util.LinkedHashSet<>();
            for (String code : text.split(",")) {
                code = code.trim().toUpperCase(java.util.Locale.US);
                if (code.matches("[A-Z]{2}")) parsed.add(code);
            }
            this.text = text;
            this.codes = java.util.Collections.unmodifiableSet(parsed);
        }
    }

    private static volatile GuardCountries guardCountries = new GuardCountries(DEFAULT_GUARD_COUNTRIES);

    /**
     * Two-letter codes of the countries whose IP addresses keep the app offline, upper case. Read on every request,
     * so the parsed set is kept until the stored text changes.
     */
    public static java.util.Set<String> getRegionGuardCountries() {
        String text = getString(REGION_GUARD_COUNTRIES, DEFAULT_GUARD_COUNTRIES);
        GuardCountries known = guardCountries;
        if (text.equals(known.text)) return known.codes;
        GuardCountries parsed = new GuardCountries(text);
        guardCountries = parsed;
        return parsed.codes;
    }

    public static void setRegionGuardCountries(java.util.Collection<String> codes) {
        StringBuilder text = new StringBuilder();
        for (String code : codes) {
            if (text.length() > 0) text.append(',');
            text.append(code);
        }
        putString(REGION_GUARD_COUNTRIES, text.toString());
    }

    public static final String REGION_HINT = "region_hint";

    /** The "?" next to "Not available in your country" on tracks, with its explanation. On by default. */
    public static boolean isRegionHintEnabled() {
        return BoolSettings.REGION_HINT.get();
    }

    public static final String REGION_RECHECK_ON_CHANGE = "region_recheck_on_change";

    /** Checks the IP country again when the network or the VPN changes. On by default. */
    public static boolean isRegionRecheckOnChangeEnabled() {
        return BoolSettings.REGION_RECHECK_ON_CHANGE.get();
    }

    public static final String REGION_RECHECK_PERIODIC = "region_recheck_periodic";

    /** Checks the IP country again every 30 seconds while requests go. On by default. */
    public static boolean isRegionRecheckPeriodicEnabled() {
        return BoolSettings.REGION_RECHECK_PERIODIC.get();
    }

    public static final String MIX_WITH_OTHER_APPS = "mix_with_other_apps";

    /** Plays without taking the audio focus, so other apps do not pause SoundCloud. Off by default. */
    public static boolean isMixWithOtherAppsEnabled() {
        return BoolSettings.MIX_WITH_OTHER_APPS.get();
    }

    public static final String INTERCEPT_DOWNLOAD_BUTTONS = "intercept_download_buttons";

    /** SoundCloud's own download buttons download with Arsound instead of offering a subscription. On by default. */
    public static boolean isInterceptDownloadButtonsEnabled() {
        return BoolSettings.INTERCEPT_DOWNLOAD_BUTTONS.get();
    }

    public static final String LYRICS_ENABLED = "lyrics_enabled";

    /** The lyrics button in the full-screen player. On by default; the lyrics themselves are shown after a tap on it. */
    public static boolean isLyricsEnabled() {
        return BoolSettings.LYRICS_ENABLED.get();
    }

    /** Whether the lyrics are shown in the player: the button's state, kept from one launch to the next. */
    public static final String LYRICS_VISIBLE = "lyrics_visible";

    /** Off until the user taps the button the first time; nothing is looked up while it is off. */
    public static boolean isLyricsVisible() {
        return BoolSettings.LYRICS_VISIBLE.get();
    }

    public static final String LYRICS_AI_ENABLED = "lyrics_ai_enabled";

    /** Recognition of the words of songs without lyrics by a neural network on the phone. Off by default. */
    public static boolean isLyricsAiEnabled() {
        return BoolSettings.LYRICS_AI_ENABLED.get();
    }

    public static void setLyricsAiEnabled(boolean enabled) {
        BoolSettings.LYRICS_AI_ENABLED.set(enabled);
    }

    /** Takes the voice out of the music before the network listens. On by default. */
    public static final String LYRICS_AI_SEPARATE = "lyrics_ai_separate";

    public static final String SCREENSHOT_SHARE = "screenshot_share";

    /** SoundCloud's "Share screenshot" sheet after a screenshot. Off by default. */
    public static boolean isScreenshotShareEnabled() {
        return BoolSettings.SCREENSHOT_SHARE.get();
    }

    public static final String THEME_EFFECTS = "theme_effects";

    /** The animated background of the chosen theme: sparks, stars, fireflies, petals. On by default. */
    public static boolean isThemeEffectsEnabled() {
        return BoolSettings.THEME_EFFECTS.get();
    }

    /** The colours of the "Custom theme" as JSON (theme/CustomTheme); empty until the user saves some. */
    public static final String CUSTOM_THEME = "arsound_custom_theme";

    /** The wallpaper behind Arsound's screens and SoundCloud's main screens (wallpaper/WallpaperStore). On once one is chosen. */
    public static final String WALLPAPER_ENABLED = "arsound_wallpaper_enabled";
    /** How much of the theme's surface colour lies over the wallpaper, in percent. */
    public static final String WALLPAPER_DIM = "arsound_wallpaper_dim";
    /** How much the wallpaper is blurred, in percent. */
    public static final String WALLPAPER_BLUR = "arsound_wallpaper_blur";
    /** What lies behind SoundCloud's top bar over the wallpaper (wallpaper/TopBarStyle). */
    public static final String WALLPAPER_TOP_BAR = "arsound_wallpaper_top_bar";
    /** The user's Wallhaven API key. Optional; only read to be sent as a header, never logged, never put into an address. */
    public static final String WALLHAVEN_KEY = "arsound_wallhaven_key";
    /** Offers sketchy and NSFW wallpapers in the Wallhaven browser. Works only with a key; off by default. */
    public static final String WALLHAVEN_ADULT = "arsound_wallhaven_adult";
    /** The last search of the Wallhaven browser as JSON (wallpaper/Wallhaven.Query), so it opens where it was left. */
    public static final String WALLHAVEN_LAST = "arsound_wallhaven_last";

    public static final String HIDE_HEADER_ARTWORK = "hide_header_artwork";
    public static final String HIDE_HOME_GREETING = "hide_home_greeting";

    /** Removes the picture behind the title of "Your likes" (the hearts) and of the purchases page. Off by default. */
    public static boolean isHeaderArtworkHidden() {
        return BoolSettings.HIDE_HEADER_ARTWORK.get();
    }

    /** How many particles the theme animation draws, in percent of the design. */
    public static final String THEME_EFFECTS_DENSITY = "theme_effects_density";

    public static final String SMOOTH_PLAYER_COLLAPSE = "smooth_player_collapse";

    /** The end of a track fades out and the next one fades in (player/Crossfade). Off by default. */
    public static final String CROSSFADE = "crossfade";
    /** How long one fade of the smooth transition takes, in seconds. */
    public static final String CROSSFADE_SECONDS = "crossfade_seconds";
    /** Every track plays slowed (player/SlowedPlayback), except one whose title says it is slowed or sped up already. Off by default. */
    public static final String SLOWED_PLAYBACK = "slowed_playback";
    /** The speed of the slowed tracks in percent; the pitch follows it. */
    public static final String SLOWED_PERCENT = "slowed_percent";
    /** When the next streamed track is loaded whole ahead (player/NextTrackPreload): 0 never, 1 on Wi-Fi, 2 always. */
    public static final String PRELOAD_NEXT = "preload_next";
    /** Hides the comments that pop up in the player while a track plays (player/PlayerComments). Off by default. */
    public static final String HIDE_PLAYER_COMMENTS = "hide_player_comments";

    /**
     * Keeps the player from doing needless work while it is pulled up and down, so it does not stutter on the way
     * down. Off puts SoundCloud's own behaviour back. On by default.
     */
    public static boolean isSmoothPlayerCollapseEnabled() {
        return BoolSettings.SMOOTH_PLAYER_COLLAPSE.get();
    }

    public static final String DUPLICATE_FILTER = "duplicate_filter";
    public static final String MERGE_EDITED_VERSIONS = "merge_edited_versions";

    public static boolean isDuplicateFilterEnabled() {
        return BoolSettings.DUPLICATE_FILTER.get();
    }

    public static boolean isMergeEditedVersions() {
        return BoolSettings.MERGE_EDITED_VERSIONS.get();
    }

    public static final String DEVELOPER_MODE = "developer_mode";
    public static final String DEVELOPER_NETWORK_DELAY = "developer_network_delay_seconds";

    public static boolean isDeveloperModeEnabled() {
        return BoolSettings.DEVELOPER_MODE.get();
    }

    public static void setDeveloperModeEnabled(boolean enabled) {
        BoolSettings.DEVELOPER_MODE.set(enabled);
    }

    /** Delay added to every HTTP request. Only active while developer mode is on. */
    public static int getDeveloperNetworkDelaySeconds() {
        SharedPreferences preferences = preferences();
        if (preferences == null || !preferences.getBoolean(DEVELOPER_MODE, false)) return 0;
        return preferences.getInt(DEVELOPER_NETWORK_DELAY, 0);
    }

    public static void setDeveloperNetworkDelaySeconds(int seconds) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;

        preferences.edit().putInt(DEVELOPER_NETWORK_DELAY, seconds).apply();
    }

    public static final String BLOCK_PLAYBACK_ADS = "block_playback_ads";

    /**
     * Controls the player-level ad request guard. Enabled by default because an ad request that
     * cannot be fulfilled otherwise leaves the player in an unnecessary loading state.
     */
    public static boolean isBlockPlaybackAdsEnabled() {
        return BoolSettings.BLOCK_PLAYBACK_ADS.get();
    }

    public static void setBlockPlaybackAdsEnabled(boolean enabled) {
        BoolSettings.BLOCK_PLAYBACK_ADS.set(enabled);
    }

    public static final String HIDE_IMPORT_BANNER = "hide_import_banner";

    /**
     * Removes the playlist import banner ("Transfer your gems") from the library. On by default:
     * the banner comes back on its own after it is closed.
     */
    public static boolean isHideImportBannerEnabled() {
        return BoolSettings.HIDE_IMPORT_BANNER.get();
    }

    public static void setHideImportBannerEnabled(boolean enabled) {
        BoolSettings.HIDE_IMPORT_BANNER.set(enabled);
    }

    /** Writes the debug log to a file, so a rare problem can be caught over several days. Off by default. */
    public static boolean isFileLoggingEnabled() {
        return app.revanced.extension.shared.debug.LogFile.isEnabled();
    }

    public static void setFileLoggingEnabled(boolean enabled) {
        app.revanced.extension.shared.debug.LogFile.setEnabled(enabled);
    }

    public static void setTelemetryEnabled(boolean enabled) {
        BoolSettings.TELEMETRY_ENABLED.set(enabled);
    }
}
