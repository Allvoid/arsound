package app.revanced.extension.soundcloud.local;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Reflect;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * Makes the hearts redraw after an imported track is liked or un-liked here.
 * <p>
 * SoundCloud keeps the set of liked tracks in a relay of {@code LikedStatuses} and builds every track row from it,
 * asking {@code isLiked} again each time it emits. A like that never reaches the database changes nothing in that
 * set, so the relay is made to emit its latest value once more.
 */
final class HeartRefresh {
    private static volatile Object stateProvider;

    private HeartRefresh() {
    }

    /** Called from the constructor of {@code LikesStateProvider}. */
    static void setStateProvider(Object provider) {
        stateProvider = provider;
    }

    /** The latest {@code LikedStatuses}, or null when there are none yet. Waits for the relay: off the main thread. */
    static Object latestStatuses() throws Exception {
        Object provider = stateProvider;
        if (provider == null) return null;
        Object observable = provider.getClass().getMethod("likedStatuses").invoke(provider);
        return Rx.blockingFirst(observable, 2, TimeUnit.SECONDS);
    }

    static void emitAgain() {
        Object provider = stateProvider;
        if (provider == null) return;
        Utils.runOnBackgroundThread(() -> {
            try {
                Object latest = latestStatuses();
                if (latest == null) return;
                Object relay = statusesRelay(provider);
                Utils.runOnMainThread(() -> accept(relay, latest));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not redraw the hearts", ex);
            }
        });
    }

    private static void accept(Object relay, Object statuses) {
        try {
            Rx.relayAccept(relay, statuses);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not redraw the hearts", ex);
        }
    }

    /** The only relay field of the provider; found by its type, as the name may change between versions. */
    private static Object statusesRelay(Object provider) throws IllegalAccessException {
        Field relay = Reflect.field(provider.getClass(), field -> field.getType().getSimpleName().equals("BehaviorRelay"));
        if (relay == null) throw new IllegalStateException("The relay of liked statuses was not found");
        return relay.get(provider);
    }
}
