package app.revanced.extension.soundcloud.offline;

import android.content.Context;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * To the user such a track looks like any other download: Arsound reads SoundCloud's own offline state of the track
 * ({@link #offlineState}) and shows it the same downloaded icon and counts it the same way, even though the audio
 * lives in SoundCloud's offline library rather than as a file in Music/Arsound.
 * <p>
 * Everything here is by reflection against SoundCloud's own classes. Names are shrunk and change between versions,
 * so only the stable interface method names ({@code makeTrackAvailableOffline}, {@code startFromUserConsumer}) and
 * field/parameter types are relied on, never the implementation's own short names.
 */
@SuppressWarnings("unused")
public final class NativeOffline {
    private static final String OFFLINE_SERVICE_INITIATOR =
            "com.soundcloud.android.foundation.domain.offline.OfflineServiceInitiator";
    private static final String TRACK_OFFLINE_STATE_PROVIDER =
            "com.soundcloud.android.offline.TrackOfflineStateProvider";
    private static final String URN_CLASS = "com.soundcloud.android.foundation.domain.Urn";

    /** SoundCloud's DefaultOfflineContentOperations, a single instance, captured in its constructor. */
    private static volatile WeakReference<Object> offlineOperations = new WeakReference<>(null);

    /** Tracks already handed to the native download this run, so a repeated tap does not queue them again. */
    private static final Set<String> requested = Collections.synchronizedSet(new HashSet<>());

    // Reflection handles are the same for the life of the process, so they are found once and kept.
    private static volatile Method urnFromString;
    private static volatile Field offlineStateProviderField;
    private static volatile Field offlineStateMapField;

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
     * Requests SoundCloud's native offline download of one track and starts the download worker. Runs on a
     * background thread; never throws.
     *
     * @param trackId The numeric track id.
     */
    public static void makeAvailable(Context context, String trackId) {
        makeAvailableAll(context, Collections.singletonList(trackId));
    }

    /**
     * Requests SoundCloud's native offline download of several tracks and starts the download worker ONCE for the
     * whole batch. Starting it per track makes SoundCloud restart the service and spam a notification for each one,
     * so the request is sent for every track first and the worker is started a single time. Never throws.
     */
    public static void makeAvailableAll(Context context, List<String> trackIds) {
        Utils.runOnBackgroundThread(() -> {
            Object operations = offlineOperations.get();
            if (operations == null) {
                DownloadTrackPatch.showToast(context, DownloadTrackPatch.text(
                        "Не удалось начать скачивание", "Could not start the download"));
                Logger.printInfo(() -> "No offline operations captured yet");
                return;
            }
            int queued = 0;
            for (String trackId : trackIds) {
                // The track may already be in SoundCloud's offline library, or queued earlier this run.
                if (!requested.add(trackId)) continue;
                String state = offlineState(trackId);
                if ("DOWNLOADED".equals(state) || "REQUESTED".equals(state) || "DOWNLOADING".equals(state)) continue;
                try {
                    Object urn = urn(operations, trackId);
                    Method make = operations.getClass().getMethod("makeTrackAvailableOffline",
                            Reflect.type(operations.getClass().getClassLoader(), URN_CLASS));
                    Object completable = make.invoke(operations, urn);
                    if (completable != null) completable.getClass().getMethod("subscribe").invoke(completable);
                    queued++;
                    Logger.printInfo(() -> "DRM track " + trackId + " requested for the native offline download");
                } catch (Throwable ex) {
                    requested.remove(trackId);
                    Logger.printException(() -> "Could not request " + trackId + " for the native offline download", ex);
                }
            }
            if (queued == 0) return;
            try {
                startWorker(operations);
            } catch (Throwable ex) {
                Logger.printException(() -> "Could not start the native offline worker", ex);
            }
            DownloadTrackPatch.showToast(context, DownloadTrackPatch.text(
                    "Скачиваю в офлайн-библиотеку SoundCloud (слушать можно в приложении)",
                    "Saving to SoundCloud's offline library (plays in the app)"));
        });
    }

    /**
     * SoundCloud's own offline state of the track, as the name of its {@code OfflineState} enum value
     * ({@code DOWNLOADED}, {@code REQUESTED}, {@code DOWNLOADING}, {@code UNAVAILABLE}), or null when the track is not
     * in SoundCloud's offline library or the state cannot be read. Cheap enough to call while a cell draws.
     */
    public static String offlineState(String trackId) {
        Object operations = offlineOperations.get();
        if (operations == null) return null;
        try {
            Object provider = offlineStateProviderField(operations).get(operations);
            if (provider == null) return null;
            Object mapValue = offlineStateMapField(provider).get(provider);
            if (!(mapValue instanceof Map)) return null;
            Object state = ((Map<?, ?>) mapValue).get(urn(operations, trackId));
            return state instanceof Enum ? ((Enum<?>) state).name() : null;
        } catch (Throwable ex) {
            return null;
        }
    }

    /** Whether the track is fully downloaded in SoundCloud's offline library. */
    public static boolean isDownloaded(String trackId) {
        return "DOWNLOADED".equals(offlineState(trackId));
    }

    /** Whether the track is queued or being downloaded in SoundCloud's offline library. */
    public static boolean isDownloading(String trackId) {
        String state = offlineState(trackId);
        return "REQUESTED".equals(state) || "DOWNLOADING".equals(state);
    }

    private static Object urn(Object operations, String trackId) throws Exception {
        Method fromString = urnFromString;
        if (fromString == null) {
            Class<?> urnClass = Reflect.type(operations.getClass().getClassLoader(), URN_CLASS);
            fromString = urnClass.getMethod("fromString", String.class);
            urnFromString = fromString;
        }
        return fromString.invoke(null, "soundcloud:tracks:" + trackId);
    }

    private static Field offlineStateProviderField(Object operations) {
        Field field = offlineStateProviderField;
        if (field == null) {
            field = Reflect.field(operations.getClass(), Reflect.typeNamed(TRACK_OFFLINE_STATE_PROVIDER));
            offlineStateProviderField = field;
        }
        return field;
    }

    private static Field offlineStateMapField(Object provider) {
        Field field = offlineStateMapField;
        if (field == null) {
            // The track-state provider keeps the urn -> OfflineState map in its one Map field.
            field = Reflect.field(provider.getClass(), Reflect.typeIs(Map.class));
            offlineStateMapField = field;
        }
        return field;
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
