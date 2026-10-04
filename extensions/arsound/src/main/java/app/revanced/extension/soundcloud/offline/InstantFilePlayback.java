package app.revanced.extension.soundcloud.offline;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Starts downloaded tracks from their file without waiting for SoundCloud.
 * <p>
 * Before playing a track SoundCloud waits for two things together: the track from its repository
 * ({@code SYNC_MISSING}, which goes to the server when the stored copy is missing or stale) and the
 * notification metadata. Neither has a timeout. On a network that accepts connections but never
 * answers (filtered or throttled), the player stayed in "buffering" forever although the file was
 * on the phone, and switching tracks queued up behind the stuck request.
 * <p>
 * Now a downloaded track gets its playback item straight from the file, and playback does not wait
 * for the metadata at all. The metadata takes about 1.5 s even on a good connection (and never comes
 * offline), and it is set on the notification by itself when it arrives, so waiting for it only
 * delayed the sound.
 * <p>
 * Instead of the metadata, playback gets Arsound's own failure right away. SoundCloud also starts
 * the current track on its first metadata if the player is playing (this is how a skip starts the
 * next track), so the own failure counts as that first metadata. Otherwise the real metadata,
 * arriving later, restarted the track that was already playing.
 */
@SuppressWarnings("unused")
public final class InstantFilePlayback {
    private static final long METADATA_WAIT_MS = 0;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    /** A failure of its own, so it can be told apart from SoundCloud's shared failure. */
    private static volatile Object instantFailure;

    private InstantFilePlayback() {
    }

    private static File downloadedFile(Object trackUrn) {
        if (trackUrn == null) return null;
        return DownloadTrackPatch.getDownloadedFile(DownloadTrackPatch.parseTrackId(trackUrn));
    }

    /**
     * Injection point. Called with the playback item SoundCloud is about to wait for.
     *
     * @param maybe           {@code Maybe<PlaybackItem>} built by SoundCloud.
     * @param trackSourceInfo The {@code TrackSourceInfo} of the queue item.
     * @param position        The start position in milliseconds.
     * @param trackUrn        The {@code TrackUrn}.
     * @return A {@code Maybe} with a file playback item, or the original one.
     */
    public static Object playbackItem(Object maybe, Object trackSourceInfo, long position, Object trackUrn) {
        File file = downloadedFile(trackUrn);
        if (file == null) return maybe;
        try {
            ClassLoader loader = maybe.getClass().getClassLoader();
            Class<?> streamClass = type(loader, "com.soundcloud.android.playback.core.stream.Stream");
            Class<?> fileStreamClass = type(loader, "com.soundcloud.android.playback.core.stream.Stream$FileStream");
            Class<?> knownClass = type(loader, "com.soundcloud.android.playback.core.stream.Metadata$Known");
            Class<?> streamsClass = type(loader, "com.soundcloud.android.playback.core.stream.Streams");
            Class<?> itemClass = type(loader, "com.soundcloud.android.playback.AudioPlaybackItem");

            // Same arguments as SoundCloud uses for imported files: no metadata, default flags.
            Object fileStream = fileStreamClass.getConstructor(String.class, knownClass, int.class)
                    .newInstance(file.getPath(), null, 14);
            Object none = type(loader, "com.soundcloud.android.playback.core.stream.Stream$None")
                    .getConstructor().newInstance();
            Object streams = streamsClass.getConstructor(streamClass, streamClass).newInstance(fileStream, none);
            Object item = itemClass.getConstructor(streamsClass, long.class, long.class,
                            type(loader, "com.soundcloud.android.playback.core.PlaybackItem$FadeOut"),
                            type(loader, "com.soundcloud.android.foundation.attribution.TrackSourceInfo"),
                            type(loader, "com.soundcloud.android.foundation.domain.TrackUrn"))
                    .newInstance(streams, position, 0L, null, trackSourceInfo, trackUrn);

            Class<?> maybeClass = type(loader, "io.reactivex.rxjava3.core.Maybe");
            for (Method method : maybeClass.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        && method.getReturnType().getSimpleName().equals("MaybeJust")) {
                    Logger.printInfo(() -> "Playing downloaded file right away: " + trackUrn);
                    return method.invoke(null, item);
                }
            }
            throw new IllegalStateException("Maybe.just not found");
        } catch (Exception ex) {
            Logger.printException(() -> "Could not play downloaded file right away", ex);
            return maybe;
        }
    }

    /**
     * Injection point. Called with the notification metadata SoundCloud waits for before playing.
     *
     * @param metadata  {@code Observable<MediaMetadataFetchResult>}.
     * @param queueItem The {@code PlayQueueItemWithContext}.
     * @return The metadata, which for downloaded tracks reports a failure if it takes too long.
     * SoundCloud then starts playback anyway.
     */
    public static Object metadata(Object metadata, Object queueItem) {
        try {
            Object urn = queueItem.getClass().getMethod("getUrn").invoke(queueItem);
            if (downloadedFile(urn) == null) return metadata;

            ClassLoader loader = metadata.getClass().getClassLoader();
            Object failure = instantFailure(loader);

            Class<?> sourceClass = type(loader, "io.reactivex.rxjava3.core.ObservableSource");
            Object delayedFailure = delayedItem(loader, sourceClass, failure);

            // merge(metadata, delayedFailure): whichever comes first is used to start playback.
            Method merge = type(loader, "io.reactivex.rxjava3.core.Observable")
                    .getMethod("F", sourceClass, sourceClass);
            return merge.invoke(null, metadata, delayedFailure);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not limit the metadata wait", ex);
            return metadata;
        }
    }

    /**
     * Injection point. Called with each notification metadata result of the current queue item.
     *
     * @param result A {@code MediaMetadataFetchResult}.
     * @return Whether it is the failure a downloaded track starts with, which SoundCloud should treat
     * as its first metadata.
     */
    public static boolean isStartWithoutMetadata(Object result) {
        return result != null && result == instantFailure;
    }

    private static Object instantFailure(ClassLoader loader) throws Exception {
        Object failure = instantFailure;
        if (failure == null) {
            Constructor<?> constructor = type(loader, "com.soundcloud.android.playback.players.queue.MediaMetadataFetchResult$Failure")
                    .getDeclaredConstructor();
            constructor.setAccessible(true);
            failure = constructor.newInstance();
            instantFailure = failure;
        }
        return failure;
    }

    /** An ObservableSource that emits one item after {@link #METADATA_WAIT_MS} (on the next main loop turn) and completes. */
    private static Object delayedItem(ClassLoader loader, Class<?> sourceClass, Object value) throws Exception {
        Class<?> disposableClass = type(loader, "io.reactivex.rxjava3.disposables.Disposable");
        Class<?> observerClass = type(loader, "io.reactivex.rxjava3.core.Observer");
        Method onSubscribe = observerClass.getMethod("onSubscribe", disposableClass);
        Method onNext = observerClass.getMethod("onNext", Object.class);
        Method onComplete = observerClass.getMethod("onComplete");

        return Proxy.newProxyInstance(loader, new Class<?>[]{sourceClass}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);

            Object observer = args[0];
            AtomicBoolean disposed = new AtomicBoolean();
            Object disposable = Proxy.newProxyInstance(loader, new Class<?>[]{disposableClass}, (p, m, a) -> {
                if (m.getDeclaringClass() == Object.class) return objectMethod(p, m, a);
                if (m.getReturnType() == boolean.class) return disposed.get();
                disposed.set(true);
                return null;
            });
            onSubscribe.invoke(observer, disposable);
            handler.postDelayed(() -> {
                if (disposed.getAndSet(true)) return;
                try {
                    Logger.printInfo(() -> "Starting the downloaded file without waiting for the metadata");
                    onNext.invoke(observer, value);
                    onComplete.invoke(observer);
                } catch (Exception ex) {
                    Logger.printException(() -> "Delayed metadata failure", ex);
                }
            }, METADATA_WAIT_MS);
            return null;
        });
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            default:
                return "Arsound" + method.getDeclaringClass().getSimpleName();
        }
    }

    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }
}
