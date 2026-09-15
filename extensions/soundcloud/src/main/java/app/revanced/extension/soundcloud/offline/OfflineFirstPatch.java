package app.revanced.extension.soundcloud.offline;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Makes playlist and album screens show the copy stored on the device immediately.
 * <p>
 * SoundCloud waits for the server before it shows a playlist created by another user, even when
 * every track is already saved offline. On a slow connection that wait can last 20 seconds per
 * request. The patched code returns the stored playlist and refreshes it in the background;
 * the screen observes the database and picks up the refreshed data by itself.
 */
@SuppressWarnings("unused")
public final class OfflineFirstPatch {
    private OfflineFirstPatch() {
    }

    public static boolean isEnabled() {
        return Settings.isOfflineFirstEnabled();
    }

    /**
     * @param repository A {@code PlaylistWithTracksRepository}.
     * @param urn        The playlist {@code Urn}.
     */
    public static void syncInBackground(Object repository, Object urn) {
        Utils.runOnBackgroundThread(() -> {
            try {
                ClassLoader loader = repository.getClass().getClassLoader();
                Class<?> urnClass = Class.forName("com.soundcloud.android.foundation.domain.Urn", false, loader);
                Class<?> playlistUrnClass = Class.forName("com.soundcloud.android.foundation.domain.PlaylistUrn", false, loader);
                Class<?> strategyClass = Class.forName("com.soundcloud.android.foundation.domain.repository.LoadStrategy", false, loader);
                Class<?> repositoryClass = Class.forName("com.soundcloud.android.foundation.domain.playlists.PlaylistWithTracksRepository", false, loader);
                Class<?> consumerClass = Class.forName("io.reactivex.rxjava3.functions.Consumer", false, loader);

                Object playlistUrn = Class.forName("com.soundcloud.android.foundation.domain.UrnKt", false, loader)
                        .getMethod("toPlaylist", urnClass).invoke(null, urn);
                Object synced = strategyClass.getField("SYNCED").get(null);
                Object observable = repositoryClass.getMethod("playlistWithTracks", playlistUrnClass, strategyClass)
                        .invoke(repository, playlistUrn, synced);

                // Errors must be consumed, otherwise RxJava passes them to its global handler, which crashes the app.
                Object ignore = Proxy.newProxyInstance(loader, new Class<?>[]{consumerClass}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        return "ArsoundIgnoreConsumer";
                    }
                    if (args != null && args.length == 1 && args[0] instanceof Throwable) {
                        Logger.printInfo(() -> "Background playlist refresh failed: " + args[0]);
                    }
                    return null;
                });
                Method subscribe = observable.getClass().getMethod("subscribe", consumerClass, consumerClass);
                subscribe.invoke(observable, ignore, ignore);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not start background playlist refresh", ex);
            }
        });
    }
}
