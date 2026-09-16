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

    private static SharedPreferences getPreferences() {
        Context context = Utils.getContext();
        if (context == null) return null;

        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isTelemetryEnabled() {
        SharedPreferences preferences = getPreferences();
        // Telemetry is off by default. If the context is not yet available, keep it off as well.
        return preferences != null && preferences.getBoolean(TELEMETRY_ENABLED, false);
    }

    public static final String HIDE_SUBSCRIPTION_OFFERS = "hide_subscription_offers";

    public static boolean isHideSubscriptionOffersEnabled() {
        SharedPreferences preferences = getPreferences();
        // On by default, the offer screen cannot load its prices outside of Google Play anyway.
        return preferences == null || preferences.getBoolean(HIDE_SUBSCRIPTION_OFFERS, true);
    }

    public static void setHideSubscriptionOffersEnabled(boolean enabled) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;

        preferences.edit().putBoolean(HIDE_SUBSCRIPTION_OFFERS, enabled).apply();
    }

    public static final String HIDE_UPGRADE_TAB = "hide_upgrade_tab";

    /** Removes the Upgrade tab from the bottom bar. Off by default. */
    public static boolean isHideUpgradeTabEnabled() {
        SharedPreferences preferences = getPreferences();
        return preferences != null && preferences.getBoolean(HIDE_UPGRADE_TAB, false);
    }

    public static void setHideUpgradeTabEnabled(boolean enabled) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;

        preferences.edit().putBoolean(HIDE_UPGRADE_TAB, enabled).apply();
    }

    public static final String UPDATE_CHECK = "update_check";

    /** Checks GitHub for a newer Arsound release on every launch. On by default. */
    public static boolean isUpdateCheckEnabled() {
        SharedPreferences preferences = getPreferences();
        return preferences == null || preferences.getBoolean(UPDATE_CHECK, true);
    }

    public static void setUpdateCheckEnabled(boolean enabled) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;

        preferences.edit().putBoolean(UPDATE_CHECK, enabled).apply();
    }

    public static final String OFFLINE_FIRST = "offline_first";

    /** Shows stored playlists before the server answers. On by default. */
    public static boolean isOfflineFirstEnabled() {
        SharedPreferences preferences = getPreferences();
        return preferences == null || preferences.getBoolean(OFFLINE_FIRST, true);
    }

    public static void setOfflineFirstEnabled(boolean enabled) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;

        preferences.edit().putBoolean(OFFLINE_FIRST, enabled).apply();
    }

    public static final String PLAY_DOWNLOADED_FILES = "play_downloaded_files";

    /** Plays tracks downloaded by Arsound from the file. On by default. */
    public static boolean isPlayDownloadedFilesEnabled() {
        SharedPreferences preferences = getPreferences();
        return preferences == null || preferences.getBoolean(PLAY_DOWNLOADED_FILES, true);
    }

    public static void setPlayDownloadedFilesEnabled(boolean enabled) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;

        preferences.edit().putBoolean(PLAY_DOWNLOADED_FILES, enabled).apply();
    }

    public static final String PLAYBACK_RETRY = "playback_retry";

    /** Retries transient playback errors automatically. On by default. */
    public static boolean isPlaybackRetryEnabled() {
        SharedPreferences preferences = getPreferences();
        return preferences == null || preferences.getBoolean(PLAYBACK_RETRY, true);
    }

    public static void setPlaybackRetryEnabled(boolean enabled) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;

        preferences.edit().putBoolean(PLAYBACK_RETRY, enabled).apply();
    }

    public static final String POWER_SAVING = "power_saving";

    /** Reduces background polling. On by default. */
    public static boolean isPowerSavingEnabled() {
        SharedPreferences preferences = getPreferences();
        return preferences == null || preferences.getBoolean(POWER_SAVING, true);
    }

    public static void setPowerSavingEnabled(boolean enabled) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;

        preferences.edit().putBoolean(POWER_SAVING, enabled).apply();
    }

    public static final String CUSTOM_DNS = "custom_dns";
    public static final String DNS_PRESET = "dns_preset";
    public static final String DNS_MODE = "dns_mode";
    public static final String CUSTOM_DOH_URL = "custom_doh_url";
    public static final String CUSTOM_DNS_SERVERS = "custom_dns_servers";

    public static boolean isCustomDnsEnabled() {
        SharedPreferences preferences = getPreferences();
        return preferences != null && preferences.getBoolean(CUSTOM_DNS, false);
    }

    public static String getDnsPreset() {
        SharedPreferences preferences = getPreferences();
        return preferences == null ? "xbox" : preferences.getString(DNS_PRESET, "xbox");
    }

    public static String getDnsMode() {
        SharedPreferences preferences = getPreferences();
        return preferences == null ? "auto" : preferences.getString(DNS_MODE, "auto");
    }

    public static String getCustomDohUrl() {
        SharedPreferences preferences = getPreferences();
        return preferences == null ? "" : preferences.getString(CUSTOM_DOH_URL, "");
    }

    public static String getCustomDnsServers() {
        SharedPreferences preferences = getPreferences();
        return preferences == null ? "" : preferences.getString(CUSTOM_DNS_SERVERS, "");
    }

    public static void putBoolean(String key, boolean value) {
        SharedPreferences preferences = getPreferences();
        if (preferences != null) preferences.edit().putBoolean(key, value).apply();
    }

    public static void putString(String key, String value) {
        SharedPreferences preferences = getPreferences();
        if (preferences != null) preferences.edit().putString(key, value).apply();
    }

    public static final String SAVED_PLAYLIST = "saved_playlist";
    public static final String SAVED_PLAYLIST_HIDDEN = "saved_playlist_hidden";

    /** The "Imported" playlist. On by default. */
    public static boolean isSavedPlaylistEnabled() {
        SharedPreferences preferences = getPreferences();
        return preferences == null || preferences.getBoolean(SAVED_PLAYLIST, true);
    }

    public static boolean isSavedPlaylistHidden() {
        SharedPreferences preferences = getPreferences();
        return preferences != null && preferences.getBoolean(SAVED_PLAYLIST_HIDDEN, false);
    }

    public static final String REGION_GUARD = "region_guard";

    public static boolean isRegionGuardEnabled() {
        SharedPreferences preferences = getPreferences();
        return preferences != null && preferences.getBoolean(REGION_GUARD, false);
    }

    public static final String DUPLICATE_FILTER = "duplicate_filter";
    public static final String MERGE_EDITED_VERSIONS = "merge_edited_versions";

    public static boolean isDuplicateFilterEnabled() {
        SharedPreferences preferences = getPreferences();
        return preferences != null && preferences.getBoolean(DUPLICATE_FILTER, false);
    }

    public static boolean isMergeEditedVersions() {
        SharedPreferences preferences = getPreferences();
        return preferences != null && preferences.getBoolean(MERGE_EDITED_VERSIONS, false);
    }

    public static final String DEVELOPER_MODE = "developer_mode";
    public static final String DEVELOPER_NETWORK_DELAY = "developer_network_delay_seconds";

    public static boolean isDeveloperModeEnabled() {
        SharedPreferences preferences = getPreferences();
        return preferences != null && preferences.getBoolean(DEVELOPER_MODE, false);
    }

    public static void setDeveloperModeEnabled(boolean enabled) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;

        preferences.edit().putBoolean(DEVELOPER_MODE, enabled).apply();
    }

    /** Delay added to every HTTP request. Only active while developer mode is on. */
    public static int getDeveloperNetworkDelaySeconds() {
        SharedPreferences preferences = getPreferences();
        if (preferences == null || !preferences.getBoolean(DEVELOPER_MODE, false)) return 0;
        return preferences.getInt(DEVELOPER_NETWORK_DELAY, 0);
    }

    public static void setDeveloperNetworkDelaySeconds(int seconds) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;

        preferences.edit().putInt(DEVELOPER_NETWORK_DELAY, seconds).apply();
    }

    public static final String BLOCK_PLAYBACK_ADS = "block_playback_ads";

    /**
     * Controls the player-level ad request guard. Enabled by default because an ad request that
     * cannot be fulfilled otherwise leaves the player in an unnecessary loading state.
     */
    public static boolean isBlockPlaybackAdsEnabled() {
        SharedPreferences preferences = getPreferences();
        return preferences == null || preferences.getBoolean(BLOCK_PLAYBACK_ADS, true);
    }

    public static void setBlockPlaybackAdsEnabled(boolean enabled) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;

        preferences.edit().putBoolean(BLOCK_PLAYBACK_ADS, enabled).apply();
    }

    public static void setTelemetryEnabled(boolean enabled) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;

        preferences.edit().putBoolean(TELEMETRY_ENABLED, enabled).apply();
    }
}
