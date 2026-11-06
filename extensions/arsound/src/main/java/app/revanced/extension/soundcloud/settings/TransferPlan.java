package app.revanced.extension.soundcloud.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What goes into the settings file and what never does: the inventory of every preferences file of Arsound, decided once
 * and in one place. Pure lists with no Android, so a test can hold the rules (no sign-in in the file, ever).
 * <p>
 * The rule for a file: it travels if it holds the user's own choices (a theme, a switch, an equalizer curve, the
 * tracks they disliked). It stays home if it holds what belongs to this phone or this run: caches, the index of
 * downloads, paths and folder grants, unfinished work, the state the system keeps for us, and every secret.
 * <p>
 * The file names are the ones the features use ({@code Settings}, {@code DislikeStore}, {@code WaveMemory}, ...); they
 * are saved on users' phones and never change, so copying them here is safe. A new preferences file stays out of the
 * export until it is added to {@link #SOURCES}; only a new <em>key</em> in a file that travels as a whole needs a look
 * at {@link #SETTINGS_NEVER}.
 */
final class TransferPlan {
    private TransferPlan() {
    }

    /** One preferences file that travels, and which of its keys do. */
    static final class Source {
        final String file;
        /** When not empty, only keys that start with one of these travel. */
        private final List<String> onlyPrefixes;
        /** Keys that never travel, whatever else is true. */
        private final Set<String> never;

        private Source(String file, List<String> onlyPrefixes, Set<String> never) {
            this.file = file;
            this.onlyPrefixes = onlyPrefixes;
            this.never = never;
        }

        /** The whole file except the keys named. */
        static Source except(String file, String... never) {
            return new Source(file, Collections.emptyList(), new HashSet<>(Arrays.asList(never)));
        }

        /** Only the keys that start with one of the prefixes. */
        static Source only(String file, String... prefixes) {
            return new Source(file, Arrays.asList(prefixes), Collections.emptySet());
        }

        /** Whether the value of this key travels. A key that looks like a secret never does, in any file. */
        boolean carries(String key) {
            if (key == null || key.isEmpty() || never.contains(key) || looksSecret(key)) return false;
            if (onlyPrefixes.isEmpty()) return true;
            for (String prefix : onlyPrefixes) {
                if (key.startsWith(prefix)) return true;
            }
            return false;
        }
    }

    /**
     * The keys of the main settings file ({@code Settings}, {@code revanced_soundcloud}) that stay on the phone.
     * Everything else in it is a choice of the user. Each entry says why:
     * <ul>
     * <li>{@code arsound_wallhaven_key}: the user's Wallhaven API key, a secret.</li>
     * <li>{@code arsound_wallhaven_adult}: only means something together with that key.</li>
     * <li>{@code arsound_wallhaven_last}: the words last searched for; a memory, not a setting.</li>
     * <li>{@code arsound_theme_forced_night}, {@code arsound_splash_style}: what the app last told Android (dark mode,
     * start screen). Android keeps the real thing, so a copy from another phone would say the work was done when it
     * was not, and the app would never do it.</li>
     * <li>{@code arsound_wallpaper_enabled}: the picture itself is not in the file, so "on" would show nothing.</li>
     * <li>{@code download_folder}: a folder of that phone (or a grant on its SD card).</li>
     * <li>{@code stream_cache_available}, {@code stream_cache_used}, {@code stream_cache_clear}: measurements and a
     * waiting order of this phone's cache.</li>
     * <li>{@code file_logging}, {@code developer_mode}, {@code developer_network_delay_seconds}, {@code playback_log}:
     * debugging switches; a copy must not switch the log or a network delay on in a new place by itself.</li>
     * <li>{@code lyrics_ai_heavy_notice_seen}: "this phone was warned that the recognition is heavy".</li>
     * </ul>
     */
    static final Set<String> SETTINGS_NEVER = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "arsound_wallhaven_key", "arsound_wallhaven_adult", "arsound_wallhaven_last",
            "arsound_theme_forced_night", "arsound_splash_style",
            "arsound_wallpaper_enabled",
            "download_folder",
            // A private DNS address can carry a personal token in the path.
            "custom_doh_url",
            "stream_cache_available", "stream_cache_used", "stream_cache_clear",
            "file_logging", "developer_mode", "developer_network_delay_seconds", "playback_log",
            "lyrics_ai_heavy_notice_seen")));

    /**
     * The preferences files that hold sign-ins. They are not in {@link #SOURCES}, so nothing of them is ever written, and
     * a settings file that names one is ignored when read:
     * <ul>
     * <li>{@code arsound_yandex}: the Yandex Music token of the transfer.</li>
     * <li>{@code arsound_youtube_account}: the cookies of the YouTube Music sign-in.</li>
     * </ul>
     * SoundCloud's own account lives in Android's account manager and in files of SoundCloud's, which are not read at all.
     */
    static final Set<String> CREDENTIAL_FILES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "arsound_yandex", "arsound_youtube_account")));

    /**
     * The files that travel, in the order they stand in the settings file.
     * <ul>
     * <li>{@code revanced_soundcloud}: every setting of the app, minus {@link #SETTINGS_NEVER}.</li>
     * <li>{@code arsound_dislikes}, {@code arsound_dislike_songs}: the tracks marked "not for me", the user's own verdicts.</li>
     * <li>{@code arsound_wave}: what the wave learned of the taste (skipped and heard tracks), bounded and short.</li>
     * <li>{@code arsound_sleep_timer}: the choices of the timer window ({@code at_clock}, the mode last used, and the
     * {@code minutes} and {@code clock} last picked); {@code stop_at} is a running timer of this phone.</li>
     * <li>{@code arsound_local_additions}: only the orders the user dragged ({@code playlist_order},
     * {@code track_order_*}). The rest of that file is tied to files on the phone (likes and additions of imported
     * music), caches (titles) or the account's playlists.</li>
     * </ul>
     * Not here, on purpose: {@code arsound_for_you} and {@code arsound_own_playlists} (playlists of one account),
     * {@code arsound_listening_stats} (a listen in progress), {@code arsound_followings}, {@code arsound_playlist_preload},
     * {@code arsound_native_offline}, {@code arsound_unavailable_tracks} (caches), {@code revanced_soundcloud_downloads},
     * {@code arsound_downloaded_playlists}, {@code revanced_soundcloud_download_parts}, {@code arsound_played_downloads},
     * {@code arsound_search_downloads}, {@code arsound_pending_deletes} (downloads), {@code arsound_local_covers},
     * {@code arsound_playlist_tracks}, {@code arsound_removed_tracks}, {@code arsound_watch_folder} (imported music and
     * folders of this phone), {@code arsound_music_access}, {@code arsound_welcome}, {@code arsound_vivo_music_card},
     * {@code arsound_app_icon} (permissions, hints and the launcher icon, which Android keeps itself),
     * {@code arsound_song_languages} (a cache), and the two files of {@link #CREDENTIAL_FILES}.
     */
    static final List<Source> SOURCES = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(
            Source.except("revanced_soundcloud", SETTINGS_NEVER.toArray(new String[0])),
            Source.except("arsound_dislikes"),
            Source.except("arsound_dislike_songs"),
            Source.except("arsound_wave"),
            Source.except("arsound_sleep_timer", "stop_at"),
            Source.only("arsound_local_additions", "playlist_order", "track_order_"))));

    /** The words in a key name that mark a secret. Checked on every key of every file, as a second lock. */
    private static final String[] SECRET_WORDS = {
            "token", "secret", "password", "passwd", "cookie", "credential", "session", "apikey", "api_key", "auth"};

    /**
     * Whether a key name says it holds a secret: a token, a password, cookies, a session, an API key (a name ending in
     * {@code _key}). A second lock behind the lists above: a secret added later under such a name is kept out even if
     * nobody remembered to list it. Costs at most a setting that is not carried over.
     */
    static boolean looksSecret(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String word : SECRET_WORDS) {
            if (lower.contains(word)) return true;
        }
        return lower.endsWith("_key");
    }

    /** The rule for a preferences file, or null if the file does not travel. */
    static Source find(String file) {
        for (Source source : SOURCES) {
            if (source.file.equals(file)) return source;
        }
        return null;
    }
}
