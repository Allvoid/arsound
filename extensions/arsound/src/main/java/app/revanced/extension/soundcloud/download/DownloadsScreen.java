package app.revanced.extension.soundcloud.download;

import android.content.Context;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * The Downloads screen of the library shows the tracks downloaded by Arsound.
 * <p>
 * The screen lists only what SoundCloud's own offline sync keeps, which needs a subscription, so it
 * stayed empty. Tracks downloaded by Arsound are added to its list as single downloaded tracks, and
 * only this screen is told they are downloaded: the rest of the app keeps asking Arsound, so the player
 * does not look for them in SoundCloud's own offline storage.
 */
@SuppressWarnings("unused")
public final class DownloadsScreen {
    private DownloadsScreen() {
    }

    /**
     * Injection point: the start of the step that filters the screen's list.
     *
     * @param prepared {@code DownloadsDataSource.PreparedDownloads}; its list gets the Arsound tracks.
     */
    public static void addArsoundTracks(Object prepared) {
        try {
            Context context = Utils.getContext();
            if (context == null) return;
            java.lang.reflect.Field field = prepared.getClass().getDeclaredField("a");
            field.setAccessible(true);
            List<Object> items = new ArrayList<>((List<?>) field.get(prepared));

            ClassLoader loader = prepared.getClass().getClassLoader();
            Class<?> urnClass = Class.forName("com.soundcloud.android.foundation.domain.Urn", false, loader);
            Class<?> trackClass = Class.forName("com.soundcloud.android.offline.data.db.SelectiveSyncTrack", false, loader);
            java.lang.reflect.Constructor<?> constructor = trackClass.getConstructor(urnClass, Date.class);
            java.lang.reflect.Method forTrack = urnClass.getMethod("forTrack", String.class);

            List<String> present = new ArrayList<>();
            for (Object item : items) present.add(String.valueOf(item.getClass().getMethod("getUrn").invoke(item)));
            int added = 0;
            for (String id : DownloadTrackPatch.getDownloadedTracks()) {
                if (DownloadTrackPatch.getDownloadState(context, id) != DownloadTrackPatch.DownloadState.DOWNLOADED) continue;
                Object urn = forTrack.invoke(null, id);
                if (present.contains(String.valueOf(urn))) continue;
                java.io.File file = DownloadTrackPatch.getDownloadedFile(id);
                // Newest downloads first, like SoundCloud sorts its own: by the time of the file.
                items.add(constructor.newInstance(urn, new Date(file != null ? file.lastModified() : 0)));
                added++;
            }
            field.set(prepared, items);
            int count = added;
            Logger.printInfo(() -> "Downloads screen: added " + count + " tracks downloaded by Arsound");
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add Arsound downloads to the Downloads screen", ex);
        }
    }

    /** The urn whose state is being asked: its register gets the state, so it is kept before the call. */
    private static final ThreadLocal<Object> askedUrn = new ThreadLocal<>();

    /** Injection point: right before {@code OfflineProperties.toOfflineState(urn)} on the screen. */
    public static void beforeState(Object urn) {
        askedUrn.set(urn);
    }

    /**
     * Injection point: the offline state the screen filters by.
     *
     * @return DOWNLOADED for a track downloaded by Arsound, otherwise the state SoundCloud gave.
     */
    public static Object stateOnScreen(Object state) {
        Object urn = askedUrn.get();
        askedUrn.remove();
        try {
            String value = String.valueOf(urn);
            if (!value.startsWith("soundcloud:tracks:")) return state;
            Context context = Utils.getContext();
            String id = value.substring("soundcloud:tracks:".length());
            if (context == null || DownloadTrackPatch.getDownloadState(context, id) != DownloadTrackPatch.DownloadState.DOWNLOADED) {
                return state;
            }
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
