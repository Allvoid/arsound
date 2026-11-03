package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TransferPlanTest {
    @Test
    public void noSignInFileIsEverRead() {
        for (String file : TransferPlan.CREDENTIAL_FILES) assertNull(file, TransferPlan.find(file));
        assertTrue(TransferPlan.CREDENTIAL_FILES.contains("arsound_yandex"));
        assertTrue(TransferPlan.CREDENTIAL_FILES.contains("arsound_youtube_account"));
    }

    @Test
    public void everySourceFileIsListedOnce() {
        Set<String> seen = new HashSet<>();
        for (TransferPlan.Source source : TransferPlan.SOURCES) {
            assertTrue(source.file + " twice", seen.add(source.file));
            assertEquals(source, TransferPlan.find(source.file));
        }
        assertEquals(6, seen.size());
    }

    @Test
    public void theMainSettingsFileTravelsButNotTheKeysOfThePhone() {
        TransferPlan.Source settings = TransferPlan.find("revanced_soundcloud");
        assertNotNull(settings);
        for (String key : Arrays.asList("arsound_theme", "crossfade", "equalizer_levels", "region_guard_countries",
                Settings.CUSTOM_THEME, Settings.WALLPAPER_DIM, Settings.THEME_EFFECTS_DENSITY, Settings.STREAM_CACHE_SIZE)) {
            assertTrue(key, settings.carries(key));
        }
        for (String key : TransferPlan.SETTINGS_NEVER) assertFalse(key, settings.carries(key));
    }

    @Test
    public void theNeverListHoldsTheKeysTheCodeNames() {
        Set<String> never = TransferPlan.SETTINGS_NEVER;
        assertTrue(never.contains(Settings.WALLHAVEN_KEY));
        assertTrue(never.contains(Settings.WALLHAVEN_ADULT));
        assertTrue(never.contains(Settings.WALLHAVEN_LAST));
        assertTrue(never.contains(Settings.WALLPAPER_ENABLED));
        assertTrue(never.contains(Settings.DEVELOPER_MODE));
        assertTrue(never.contains(Settings.DEVELOPER_NETWORK_DELAY));
        // Written by the features themselves; the names are saved on users' phones.
        assertTrue(never.contains("download_folder"));
        assertTrue(never.contains("arsound_theme_forced_night"));
        assertTrue(never.contains("arsound_splash_style"));
        assertTrue(never.contains("stream_cache_available"));
        assertTrue(never.contains("stream_cache_used"));
        assertTrue(never.contains("stream_cache_clear"));
        assertTrue(never.contains("file_logging"));
        assertTrue(never.contains("playback_log"));
    }

    @Test
    public void theSleepTimerSharesItsChoicesButNotTheRunningTimer() {
        TransferPlan.Source timer = TransferPlan.find("arsound_sleep_timer");
        assertTrue(timer.carries("at_clock"));
        assertTrue(timer.carries("minutes"));
        assertTrue(timer.carries("clock"));
        assertFalse(timer.carries("stop_at"));
    }

    @Test
    public void ofTheLocalFileOnlyTheDraggedOrdersTravel() {
        TransferPlan.Source local = TransferPlan.find("arsound_local_additions");
        assertTrue(local.carries("playlist_order"));
        assertTrue(local.carries("track_order_soundcloud:playlists:123"));
        for (String key : Arrays.asList("additions", "recent_playlists", "titles", "titles_missing", "likes_known", "likes_liked",
                "likes_unliked", "saved_playlist_urn", "saved_playlist_duplicates", "covers_squared")) {
            assertFalse(key, local.carries(key));
        }
    }

    @Test
    public void noSourceCarriesAnEmptyKey() {
        for (TransferPlan.Source source : TransferPlan.SOURCES) {
            assertFalse(source.carries(""));
            assertFalse(source.carries(null));
        }
    }

    @Test
    public void aKeyThatLooksSecretNeverTravelsInAnyFile() {
        List<String> secrets = Arrays.asList("token", "access_token", "Token", "yandex_token", "client_secret", "password",
                "user_passwd", "cookies", "youtube_cookie", "session", "session_id", "SessionKey", "api_key", "apikey",
                "arsound_wallhaven_key", "my_key", "oauth", "auth_header", "credentials");
        for (String key : secrets) {
            assertTrue(key, TransferPlan.looksSecret(key));
            for (TransferPlan.Source source : TransferPlan.SOURCES) assertFalse(source.file + "/" + key, source.carries(key));
        }
    }

    @Test
    public void theKeysOfRealSettingsAreNotTakenForSecrets() {
        // Names the app really saves; a word list that caught one of them would silently drop a setting.
        List<String> real = Arrays.asList("arsound_theme", "arsound_player_skin", "arsound_custom_theme", "arsound_custom_theme_recent",
                "equalizer_enabled", "equalizer_levels", "equalizer_user_presets", "fx_speed", "fx_speedup_percent",
                "stream_cache_size", "stream_cache_days", "wave_src_lastfm", "wave_src_soundcloud", "for_you_enabled",
                "dislikes_enabled", "region_guard_countries", "region_hint", "downloads_unplayed_days", "listening_stats",
                "lyrics_ai_separate", "hide_tab_search", "datadome_prompt", "custom_doh_url", "custom_dns_servers",
                "widget_heart_cover", "arsound_player_track_colors", "stats_period", "playlist_limit_hint", "crossfade_seconds",
                "slowed_percent", "preload_next", "hide_header_artwork", "ledger", "at_clock", "playlist_order",
                "soundcloud:tracks:1234567", "track_order_soundcloud:playlists:42");
        for (String key : real) assertFalse(key, TransferPlan.looksSecret(key));
    }
}
