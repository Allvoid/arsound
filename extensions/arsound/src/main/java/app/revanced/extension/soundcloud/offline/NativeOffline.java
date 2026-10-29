package app.revanced.extension.soundcloud.offline;

import android.content.Context;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.shared.Reflect;

/**
 * Hands a DRM-protected track to SoundCloud's own offline download, which Arsound's own downloader cannot save
 * (the Widevine/FairPlay key comes from a licence server behind the CDM). SoundCloud downloads the encrypted
 * stream and fetches a persistable licence, and the track then plays offline inside the app through the CDM.
 * <p>
 * The track does not appear in the Arsound Downloads screen or as a file in Music/Arsound: it lives in
 * SoundCloud's own offline library and plays only in the app.
 * <p>
 * Everything here is by reflection against SoundCloud's own classes. Names are shrunk and change between versions,
 * so only the stable interface method names ({@code makeTrackAvailableOffline}, {@code startFromUserConsumer}) and
 * field/parameter types are relied on, never the implementation's own short names.
 */
@SuppressWarnings("unused")
public final class NativeOffline {
    private static final String OFFLINE_SERVICE_INITIATOR =
            "com.soundcloud.android.foundation.domain.offline.OfflineServiceInitiator";
    private static final String URN_CLASS = "com.soundcloud.android.foundation.domain.Urn";

    /** SoundCloud's DefaultOfflineContentOperations, a single instance, captured in its constructor. */
    private static volatile WeakReference<Object> offlineOperations = new WeakReference<>(null);

    /** Tracks already handed to the native download this run, so a repeated tap does not queue them again. */
    private static final Set<String> requested = Collections.synchronizedSet(new HashSet<>());

    private NativeOffline() {
    }

    /** Injection point: the end of the constructor of SoundCloud's offline content operations. */
    public static void setOfflineOperations(Object operations) {
        offlineOperations = new WeakReference<>(operations);
    }

    /** Whether SoundCloud's offline operations have been seen yet, so a DRM track can be handed to them. */
    public static boolean isAvailable() {
        return offlineOperations.get() != null;
    }

    /**
     * Requests SoundCloud's native offline download of one track and starts the download worker (the worker is not
     * started by the request alone while the offline feature is off). Runs on a background thread; never throws.
     *
     * @param trackId The numeric track id.
     */
    public static void makeAvailable(Context context, String trackId) {
        if (!requested.add(trackId)) return;

        Utils.runOnBackgroundThread(() -> {
            Object operations = offlineOperations.get();
            if (operations == null) {
                requested.remove(trackId);
                DownloadTrackPatch.showToast(context, DownloadTrackPatch.text(
                        "Не удалось начать скачивание этого трека", "Could not start the download of this track"));
                Logger.printInfo(() -> "No offline operations captured yet for " + trackId);
                return;
            }
            try {
                ClassLoader loader = operations.getClass().getClassLoader();
                Class<?> urnClass = Reflect.type(loader, URN_CLASS);
                Method fromString = urnClass.getMethod("fromString", String.class);
                Object urn = fromString.invoke(null, "soundcloud:tracks:" + trackId);

                // Request the track for offline: returns a Completable that must be subscribed to.
                Method make = operations.getClass().getMethod("makeTrackAvailableOffline", urnClass);
                Object completable = make.invoke(operations, urn);
                if (completable != null) completable.getClass().getMethod("subscribe").invoke(completable);

                // Start the download worker. The request only records the track; the worker is normally started by a
                // controller that is active only while the offline feature is on, which it is not here.
                startWorker(operations);

                Logger.printInfo(() -> "DRM track " + trackId + " handed to the native offline download");
                DownloadTrackPatch.showToast(context, DownloadTrackPatch.text(
                        "Скачиваю в офлайн-библиотеку SoundCloud (слушать можно в приложении)",
                        "Saving to SoundCloud's offline library (plays in the app)"));
            } catch (Throwable ex) {
                requested.remove(trackId);
                Logger.printException(() -> "Could not hand " + trackId + " to the native offline download", ex);
                DownloadTrackPatch.showToast(context, DownloadTrackPatch.text(
                        "Не удалось скачать трек с защитой DRM", "Could not download the DRM-protected track"));
            }
        });
    }

    /** Finds the OfflineServiceInitiator field by its type and calls its startFromUserConsumer(). */
    private static void startWorker(Object operations) throws Exception {
        Field initiatorField = Reflect.field(operations.getClass(), Reflect.typeNamed(OFFLINE_SERVICE_INITIATOR));
        if (initiatorField == null) {
            Logger.printInfo(() -> "No OfflineServiceInitiator field on " + operations.getClass().getName());
            return;
        }
        Object initiator = initiatorField.get(operations);
        if (initiator == null) return;
        initiator.getClass().getMethod("startFromUserConsumer").invoke(initiator);
    }
}
