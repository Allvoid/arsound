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
import app.revanced.extension.soundcloud.shared.Rx;

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

    /** Injection point: SoundCloud's downloader reports a failed download of one track. Only logs it. */
    public static void onDownloadError(Object error) {
        Logger.printInfo(() -> "SoundCloud's offline download failed: " + error);
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
                // The track may already be in SoundCloud's offline library. A request of this run that is not there
                // any more (it failed, or the copy was removed in SoundCloud) is made again, not skipped until a restart.
                String state = offlineState(trackId);
                if ("DOWNLOADED".equals(state) || "REQUESTED".equals(state) || "DOWNLOADING".equals(state)) continue;
                requested.add(trackId);
                try {
                    Object urn = urn(operations, trackId);
                    Method make = operations.getClass().getMethod("makeTrackAvailableOffline",
                            Reflect.type(operations.getClass().getClassLoader(), URN_CLASS));
                    Object completable = make.invoke(operations, urn);
                    // A failure that comes later lets the track be asked for again.
                    if (completable != null) {
                        Rx.subscribeIgnoringErrors(completable, error -> {
                            requested.remove(trackId);
                            Logger.printInfo(() -> "The native offline request of " + trackId + " failed: " + error);
                        });
                    }
                    queued++;
                    watch(context, trackId);
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
                    "Трек защищён (DRM): передаю его в офлайн-библиотеку SoundCloud, слушать можно будет в приложении",
                    "The track is protected (DRM): handing it to SoundCloud's offline library, it will play in the app"));
        });
    }

    // region Watching the requests

    /**
     * The requests of the native download, by track id, with the time each was made, kept over a restart. SoundCloud
     * itself never gives up on a request: when its downloader fails (the offline licence of a DRM track is refused to an
     * account without Go+, a server error) it puts the track back to "requested", and the spinner turned for good.
     */
    private static final String PREFERENCES = "arsound_native_offline";
    /**
     * A request still only "requested" this long after it was made and after the downloader last worked, on a network
     * SoundCloud downloads on, has failed.
     */
    private static final long FAIL_AFTER_MS = 2 * 60_000;
    private static final long CHECK_EVERY_MS = 30_000;
    private static volatile boolean checking;
    /**
     * The last time SoundCloud's downloader was seen at work (a track of its library downloading). A request waits in
     * its queue while other tracks download; it has failed only when the downloader stood still that long.
     */
    private static volatile long busyAt;
    /** Tracks the user was told wait for Wi-Fi, so it is said once. */
    private static final Set<String> toldWifi = Collections.synchronizedSet(new HashSet<>());

    private static android.content.SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static void watch(Context context, String trackId) {
        preferences(context).edit().putLong(trackId, System.currentTimeMillis()).apply();
        scheduleCheck(context.getApplicationContext());
    }

    /** Called when the app starts: requests of an earlier run are followed again. */
    public static void onAppStart(Context context) {
        if (!preferences(context).getAll().isEmpty()) scheduleCheck(context.getApplicationContext());
    }

    private static void scheduleCheck(Context context) {
        if (checking) return;
        checking = true;
        Utils.runOnMainThreadDelayed(() -> Utils.runOnBackgroundThread(() -> check(context)), CHECK_EVERY_MS);
    }

    private static void check(Context context) {
        checking = false;
        if (offlineOperations.get() == null) {
            // SoundCloud's offline part is not made yet this run: asked again later.
            scheduleCheck(context);
            return;
        }
        Map<String, ?> watched = preferences(context).getAll();
        boolean metered = isMetered(context);
        if (isDownloaderBusy()) busyAt = System.currentTimeMillis();
        for (Map.Entry<String, ?> entry : watched.entrySet()) {
            String trackId = entry.getKey();
            long requestedAt = entry.getValue() instanceof Long ? (Long) entry.getValue() : 0;
            String state = offlineState(trackId);
            if ("DOWNLOADING".equals(state)) continue;
            if (!"REQUESTED".equals(state)) {
                // Downloaded, or gone from the library (removed in SoundCloud): nothing more to follow.
                forget(context, trackId);
                if ("UNAVAILABLE".equals(state)) {
                    DownloadTrackPatch.showToast(context, DownloadTrackPatch.text(
                            "SoundCloud не даёт сохранить этот трек офлайн", "SoundCloud does not let this track be saved offline"));
                }
                continue;
            }
            if (metered) {
                // SoundCloud downloads only over Wi-Fi unless told otherwise in its settings: the request waits for it.
                if (toldWifi.add(trackId)) {
                    DownloadTrackPatch.showToast(context, DownloadTrackPatch.text(
                            "Защищённый трек SoundCloud скачает по Wi-Fi", "SoundCloud downloads the protected track over Wi-Fi"));
                }
                continue;
            }
            if (System.currentTimeMillis() - Math.max(requestedAt, busyAt) < FAIL_AFTER_MS) continue;
            Logger.printInfo(() -> "The native offline download of " + trackId + " is still requested after "
                    + (System.currentTimeMillis() - requestedAt) / 1000 + " s on Wi-Fi: it failed, the request is taken back");
            cancel(context, trackId);
            DownloadTrackPatch.showToast(context, DownloadTrackPatch.text(
                    "Не удалось сохранить защищённый трек: SoundCloud отдаёт такие треки для офлайна только с подпиской Go+",
                    "Could not save the protected track: SoundCloud gives such tracks for offline only with Go+"));
        }
        if (!preferences(context).getAll().isEmpty()) scheduleCheck(context);
    }

    /** Whether any track of SoundCloud's offline library is being downloaded now. */
    private static boolean isDownloaderBusy() {
        Object operations = offlineOperations.get();
        if (operations == null) return false;
        try {
            Object provider = offlineStateProviderField(operations).get(operations);
            Object mapValue = provider == null ? null : offlineStateMapField(provider).get(provider);
            if (!(mapValue instanceof Map)) return false;
            for (Object state : ((Map<?, ?>) mapValue).values()) {
                if (state instanceof Enum && "DOWNLOADING".equals(((Enum<?>) state).name())) return true;
            }
        } catch (Throwable ex) {
            return false;
        }
        return false;
    }

    private static void forget(Context context, String trackId) {
        preferences(context).edit().remove(trackId).apply();
        toldWifi.remove(trackId);
    }

    private static boolean isMetered(Context context) {
        try {
            android.net.ConnectivityManager manager =
                    (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            return manager != null && manager.isActiveNetworkMetered();
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Takes the track out of SoundCloud's offline library request: the spinner stops, and a tap on "Download" asks
     * again. SoundCloud forgets the request, and its worker, started here, takes the track's "requested" state away.
     * Never throws; may block briefly.
     */
    public static void cancel(Context context, String trackId) {
        forget(context, trackId);
        requested.remove(trackId);
        Object operations = offlineOperations.get();
        if (operations == null) return;
        try {
            Method take = operations.getClass().getMethod("makeTrackNotAvailableOffline",
                    Reflect.type(operations.getClass().getClassLoader(), URN_CLASS));
            Object completable = take.invoke(operations, urn(operations, trackId));
            if (completable != null) {
                Rx.subscribeIgnoringErrors(completable, error ->
                        Logger.printInfo(() -> "Could not take back the native offline request of " + trackId + ": " + error));
            }
            startWorker(operations);
            Logger.printInfo(() -> "The native offline request of " + trackId + " is taken back");
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not take back the native offline request of " + trackId, ex);
        }
    }

    // endregion

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
