package app.revanced.extension.soundcloud.offline;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.local.SavedPlaylist;
import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * Saves the contents of every playlist and album in the library ahead of time, as text only:
 * titles, artists, durations and artwork links go into SoundCloud's own database, no audio and no
 * images. With "Offline first playlists" a playlist then opens instantly, also without a network.
 * <p>
 * Runs in the background after start, one playlist at a time with a pause, and refreshes a playlist
 * at most once a day.
 */
@SuppressWarnings("unused")
public final class PlaylistPreloader {
    private static final String PREFERENCES_NAME = "arsound_playlist_preload";
    private static final long START_DELAY_MS = 15_000;
    private static final long PAUSE_MS = 1_500;
    private static final long REFRESH_AFTER_MS = TimeUnit.HOURS.toMillis(24);
    private static final long SYNC_TIMEOUT_S = 60;

    private static volatile Object myPlaylistOperations;
    private static volatile boolean started;

    private PlaylistPreloader() {
    }

    /** Injection point. Called from the constructor of {@code MyPlaylistOperations}. */
    public static void setMyPlaylistOperations(Object instance) {
        myPlaylistOperations = instance;
        if (started) return;
        started = true;
        Utils.runOnBackgroundThread(() -> {
            try {
                Thread.sleep(START_DELAY_MS);
                if (!"RU".equals(RegionGuard.lastCountry()) || !Settings.isRegionGuardEnabled()) {
                    app.revanced.extension.soundcloud.download.DownloadTrackPatch.rememberOldFileNames();
                }
                preload();
            } catch (Exception ex) {
                Logger.printException(() -> "Playlist preload failed", ex);
            }
        });
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static void preload() throws Exception {
        if (!Settings.isPlaylistPreloadEnabled()) return;
        Object operations = myPlaylistOperations;
        Object playlistOperations = SavedPlaylist.playlistOperations();
        SharedPreferences preferences = preferences();
        if (operations == null || playlistOperations == null || preferences == null) {
            Logger.printInfo(() -> "Playlist preload: app not ready");
            return;
        }
        if ("RU".equals(RegionGuard.lastCountry()) && Settings.isRegionGuardEnabled()) {
            Logger.printInfo(() -> "Playlist preload: SoundCloud is switched off, skipped");
            return;
        }

        ClassLoader loader = operations.getClass().getClassLoader();
        List<String> urns = libraryPlaylists(operations, loader);
        Object syncInitiator = syncInitiator(playlistOperations, loader);
        Class<?> urnClass = Class.forName("com.soundcloud.android.foundation.domain.Urn", false, loader);

        int synced = 0, skipped = 0;
        for (String urn : urns) {
            if (!Settings.isPlaylistPreloadEnabled()) return;
            long last = preferences.getLong(urn, 0);
            if (System.currentTimeMillis() - last < REFRESH_AFTER_MS) {
                skipped++;
                continue;
            }
            String id = urn.substring(urn.lastIndexOf(':') + 1);
            Object playlistUrn = urnClass.getMethod("forPlaylist", String.class).invoke(null, id);
            Object single = syncInitiator.getClass().getMethod("d", urnClass).invoke(syncInitiator, playlistUrn);
            Object result = Rx.blockingFirst(single, SYNC_TIMEOUT_S, TimeUnit.SECONDS);
            if (result != null) {
                preferences.edit().putLong(urn, System.currentTimeMillis()).apply();
                synced++;
            }
            Thread.sleep(PAUSE_MS);
        }
        int total = urns.size(), done = synced, fresh = skipped;
        Logger.printInfo(() -> "Playlist preload: " + total + " in library, saved " + done + ", already fresh " + fresh);
    }

    /** Urns of the playlists and albums in the library, as SoundCloud lists them (liked and own). */
    private static List<String> libraryPlaylists(Object operations, ClassLoader loader) throws Exception {
        Class<?> optionsClass = Class.forName("com.soundcloud.android.foundation.domain.playable.PlaylistsOptions", false, loader);
        Class<?> sortByClass = Class.forName("com.soundcloud.android.foundation.domain.playable.SortBy", false, loader);
        Class<?> markerClass = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker", false, loader);
        Constructor<?> defaults = optionsClass.getConstructor(sortByClass, boolean.class, boolean.class, boolean.class, int.class, markerClass);
        // Sorting is only the order of the list; the flags keep their defaults (likes and posts).
        Object sortBy = ((Object[]) sortByClass.getMethod("values").invoke(null))[0];
        Object options = defaults.newInstance(sortBy, false, false, false, 0b1110, null);

        Class<?> filterClass = Class.forName("com.soundcloud.android.foundation.domain.playable.FilterAndSortOptions", false, loader);
        Class<?> strategyClass = Class.forName("com.soundcloud.android.foundation.domain.repository.LoadStrategy", false, loader);
        Object localOnly = strategyClass.getMethod("valueOf", String.class).invoke(null, "LOCAL_ONLY");
        Object observable = operations.getClass().getMethod("myPlaylists", filterClass, strategyClass)
                .invoke(operations, options, localOnly);

        Object list = Rx.blockingFirst(observable, 30, TimeUnit.SECONDS);
        List<String> urns = new ArrayList<>();
        if (list instanceof List) {
            for (Object item : (List<?>) list) {
                String urn = String.valueOf(item.getClass().getMethod("getUrn").invoke(item));
                // The saved local-music playlist has no server tracks to save.
                if (!SavedPlaylist.isSavedPlaylist(urn)) urns.add(urn);
            }
        }
        return urns;
    }

    private static Object syncInitiator(Object playlistOperations, ClassLoader loader) throws Exception {
        Class<?> type = Class.forName("com.soundcloud.android.sync.SyncInitiator", false, loader);
        for (Field field : playlistOperations.getClass().getDeclaredFields()) {
            if (field.getType() == type) {
                field.setAccessible(true);
                return field.get(playlistOperations);
            }
        }
        throw new IllegalStateException("SyncInitiator not found");
    }
}
