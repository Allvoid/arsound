package app.revanced.extension.soundcloud.download;

import android.content.Context;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.CallSlot;
import app.revanced.extension.soundcloud.shared.Reflect;
import app.revanced.extension.soundcloud.shared.Urns;

/**
 * The Downloads screen of the library shows the tracks and playlists downloaded by Arsound.
 * <p>
 * The screen lists only what SoundCloud's own offline sync keeps, which needs a subscription, so it
 * stayed empty. Tracks downloaded by Arsound are added to its list as single downloaded tracks, and
 * only this screen is told they are downloaded: the rest of the app keeps asking Arsound, so the player
 * does not look for them in SoundCloud's own offline storage.
 * <p>
 * Playlists downloaded with "Check track downloads" are added the same way; the screen shows those
 * the app has stored, that is, opened at least once.
 */
@SuppressWarnings("unused")
public final class DownloadsScreen {
    private DownloadsScreen() {
    }

    /**
     * Injection point: the start of the step that filters the screen's list.
     *
     * @param prepared {@code DownloadsDataSource.PreparedDownloads}; its list gets the Arsound tracks and playlists.
     */
    public static void addArsoundTracks(Object prepared) {
        try {
            Context context = Utils.getContext();
            if (context == null) return;
            java.lang.reflect.Field field = prepared.getClass().getDeclaredField("a");
            field.setAccessible(true);
            List<Object> items = new ArrayList<>((List<?>) field.get(prepared));

            ClassLoader loader = prepared.getClass().getClassLoader();
            Class<?> urnClass = Reflect.type(loader, "com.soundcloud.android.foundation.domain.Urn");
            Class<?> trackClass = Reflect.type(loader, "com.soundcloud.android.offline.data.db.SelectiveSyncTrack");
            java.lang.reflect.Constructor<?> constructor = trackClass.getConstructor(urnClass, Date.class);
            java.lang.reflect.Method forTrack = urnClass.getMethod("forTrack", String.class);

            Set<String> present = new HashSet<>();
            for (Object item : items) present.add(String.valueOf(item.getClass().getMethod("getUrn").invoke(item)));
            int added = 0;
            for (String id : DownloadTrackPatch.getDownloadedTracks()) {
                if (DownloadTrackPatch.getDownloadState(context, id) != DownloadTrackPatch.DownloadState.DOWNLOADED) continue;
                Object urn = forTrack.invoke(null, id);
                if (present.contains(String.valueOf(urn))) continue;
                DownloadIndex.Entry file = DownloadIndex.known(id);
                // Newest downloads first, like SoundCloud sorts its own: by the time of the file.
                items.add(constructor.newInstance(urn, new Date(file != null ? file.modifiedMs() : 0)));
                added++;
            }
            // A playlist is a collectable just like a track: an urn and a date. The screen then loads
            // the stored playlist by its urn.
            java.lang.reflect.Method forPlaylist = urnClass.getMethod("forPlaylist", String.class);
            int addedPlaylists = 0;
            for (java.util.Map.Entry<String, Long> playlist : DownloadPlaylistPatch.getDownloadedPlaylists().entrySet()) {
                Object urn = forPlaylist.invoke(null, playlist.getKey());
                if (present.contains(String.valueOf(urn))) continue;
                items.add(constructor.newInstance(urn, new Date(playlist.getValue())));
                addedPlaylists++;
            }
            field.set(prepared, items);
            int count = added;
            int playlists = addedPlaylists;
            Logger.printInfo(() -> "Downloads screen: added " + count + " tracks and " + playlists + " playlists downloaded by Arsound");
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add Arsound downloads to the Downloads screen", ex);
        }
    }

    private static volatile Object offlinePropertiesProvider;

    /** Injection point: the constructor of {@code DefaultOfflinePropertiesProvider}. */
    public static void setOfflinePropertiesProvider(Object provider) {
        offlinePropertiesProvider = provider;
    }

    /**
     * Makes an open Downloads screen filter its list again, after Arsound downloaded or deleted files.
     * The screen listens to the offline states: the last state is sent again, which changes nothing
     * but lets the screen ask Arsound anew.
     */
    public static void refresh() {
        Object provider = offlinePropertiesProvider;
        if (provider == null) return;
        try {
            Object latest = provider.getClass().getMethod("latest").invoke(provider);
            java.lang.reflect.Field field = Reflect.field(provider.getClass(), Reflect.typeNameEndsWith(".BehaviorSubject"));
            if (field != null) {
                Object subject = field.get(provider);
                subject.getClass().getMethod("onNext", Object.class).invoke(subject, latest);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not refresh the Downloads screen", ex);
        }
    }

    private static boolean isDownloadedByArsound(String urn) {
        if (urn.startsWith(Urns.PLAYLIST_PREFIX)) {
            return DownloadPlaylistPatch.isPlaylistDownloaded(urn.substring(Urns.PLAYLIST_PREFIX.length()));
        }
        Context context = Utils.getContext();
        return context != null && urn.startsWith(Urns.TRACK_PREFIX) && DownloadTrackPatch.getDownloadState(context,
                urn.substring(Urns.TRACK_PREFIX.length())) == DownloadTrackPatch.DownloadState.DOWNLOADED;
    }

    /** The urn whose state is being asked: its register gets the state, so it is kept before the call. */
    private static final CallSlot<Object> askedUrn = new CallSlot<>();

    /** Injection point: right before {@code OfflineProperties.toOfflineState(urn)} on the screen. */
    public static void beforeState(Object urn) {
        askedUrn.put(urn);
    }

    /**
     * Injection point: the offline state the screen filters by.
     *
     * @return DOWNLOADED for a track or playlist downloaded by Arsound, otherwise the state SoundCloud gave.
     */
    public static Object stateOnScreen(Object state) {
        Object urn = askedUrn.take();
        try {
            if (!isDownloadedByArsound(String.valueOf(urn))) return state;
            Class<?> stateClass = state.getClass().isEnum() ? state.getClass() : state.getClass().getSuperclass();
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object downloaded = Enum.valueOf((Class<Enum>) stateClass, "DOWNLOADED");
            return downloaded;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not give the downloaded state", ex);
            return state;
        }
    }
}
