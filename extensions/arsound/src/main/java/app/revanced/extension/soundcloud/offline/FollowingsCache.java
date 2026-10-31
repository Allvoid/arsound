package app.revanced.extension.soundcloud.offline;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.CallSlot;
import app.revanced.extension.soundcloud.shared.Getters;
import app.revanced.extension.soundcloud.shared.Reflect;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * The Following screen of the library, without waiting for the network.
 * <p>
 * SoundCloud loads the followed profiles page by page from the server every time, so offline the screen
 * only showed "Loading error". Every loaded page is remembered here as a list of user urns; the next
 * time the stored profiles (names and pictures are in SoundCloud's database) are shown right away, and
 * the server answer replaces them when it comes. A failed server request then changes nothing.
 */
@SuppressWarnings("unused")
public final class FollowingsCache {
    private static final String PREFERENCES_NAME = "arsound_followings";

    /** The arguments of the call being built: {@code UserProfileOperations}, user urn, next page link. */
    private static final CallSlot<Object[]> call = new CallSlot<>();

    private FollowingsCache() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** Injection point: the start of {@code UserProfileOperations.hotPagedFollowings(user, nextPageLink)}. */
    public static void begin(Object operations, Object user, Object nextPageLink) {
        call.put(new Object[]{operations, user, nextPageLink});
    }

    /**
     * Injection point: the result of {@code hotPagedFollowings}.
     *
     * @param remote {@code Observable<ApiCollection<UserItem>>} from the server.
     * @return The stored first page followed by the server answer, or the server answer remembered.
     */
    public static Object wrap(Object remote) {
        Object[] args = call.take();
        if (args == null || !OfflineFirstPatch.isEnabled()) return remote;
        try {
            String key = String.valueOf(args[1]);
            boolean firstPage = args[2] == null;
            Object remembered = Rx.mapObservable(remote, page -> {
                remember(key, page, firstPage);
                return page;
            });
            if (!firstPage) return remembered;

            List<String> stored = stored(key);
            if (stored.isEmpty()) return remembered;
            Logger.printInfo(() -> "Following shown from storage: " + stored.size() + " profiles");
            return Rx.localUntilRemote(storedPage(args[0], stored), Rx.ignoreErrors(remembered));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show stored followings", ex);
            return remote;
        }
    }

    /** The {@code FollowingStateProvider} of the call being built. */
    private static final CallSlot<Object> statusProvider = new CallSlot<>();

    /** Injection point: the start of {@code FollowingStateProvider.followingStatuses()}. */
    public static void beginStatuses(Object provider) {
        statusProvider.put(provider);
    }

    /**
     * Injection point: the result of {@code followingStatuses()}. It first syncs the followings with the
     * server, which never ends offline, and every screen with profiles waited for it. The statuses stored
     * on the device are given right away; the result after the sync follows when it comes.
     */
    public static Object wrapStatuses(Object synced) {
        Object provider = statusProvider.take();
        if (provider == null || !OfflineFirstPatch.isEnabled()) return synced;
        try {
            java.lang.reflect.Field field = provider.getClass().getDeclaredField("statuses");
            field.setAccessible(true);
            return Rx.localUntilRemote(field.get(provider), synced);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not give stored following statuses", ex);
            return synced;
        }
    }

    private static List<String> stored(String key) {
        SharedPreferences preferences = preferences();
        List<String> urns = new ArrayList<>();
        String value = preferences == null ? null : preferences.getString(key, null);
        if (value == null || value.isEmpty()) return urns;
        for (String urn : value.split("\n")) if (!urn.isEmpty()) urns.add(urn);
        return urns;
    }

    /** The urns of the walk through the pages under way, per profile; main thread or the server's thread. */
    private static final java.util.Map<String, java.util.LinkedHashSet<String>> walks = new java.util.HashMap<>();

    /**
     * Keeps the urns of a loaded page. A walk through the pages replaces the stored list only when it reaches the last
     * page: until then the stored list keeps the profiles of the pages not seen again yet (offline, after a first page
     * alone, the whole list stayed one page long). It is written at the first page and at the last, not at every page.
     */
    private static void remember(String key, Object page, boolean firstPage) {
        try {
            List<?> items = (List<?>) Getters.get(page, "getCollection");
            boolean lastPage = Getters.get(page, "getNextPageLink") == null;
            java.util.LinkedHashSet<String> walk;
            synchronized (walks) {
                walk = firstPage ? null : walks.get(key);
                if (walk == null) {
                    walk = new java.util.LinkedHashSet<>();
                    // A walk that went on after the app restarted starts from what was stored.
                    if (!firstPage) walk.addAll(stored(key));
                    walks.put(key, walk);
                }
                for (Object item : items) walk.add(String.valueOf(Getters.get(item, "getUrn")));
                if (lastPage) walks.remove(key);
            }
            List<String> urns;
            if (lastPage) {
                urns = new ArrayList<>(walk);
            } else if (firstPage) {
                // The first page in front, the rest of the stored list after it, until the walk is done.
                java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(walk);
                merged.addAll(stored(key));
                urns = new ArrayList<>(merged);
            } else {
                return;
            }
            SharedPreferences preferences = preferences();
            if (preferences != null) preferences.edit().putString(key, String.join("\n", urns)).apply();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not remember followings", ex);
        }
    }

    /** {@code liveEntities.liveUsers(urns)} as a single page without a next page. */
    private static Object storedPage(Object operations, List<String> stored) throws Exception {
        ClassLoader loader = operations.getClass().getClassLoader();
        Class<?> urnClass = Reflect.type(loader, "com.soundcloud.android.foundation.domain.Urn");
        Method forUser = urnClass.getMethod("forUser", String.class);
        List<Object> urns = new ArrayList<>();
        for (String urn : stored) urns.add(forUser.invoke(null, urn.substring(urn.lastIndexOf(':') + 1)));

        java.lang.reflect.Field field = operations.getClass().getDeclaredField("liveEntities");
        field.setAccessible(true);
        Object liveEntities = field.get(operations);
        Class<?> liveClass = Reflect.type(loader, "com.soundcloud.android.foundation.domain.LiveEntities");
        Object users = liveClass.getMethod("liveUsers", List.class).invoke(liveEntities, urns);

        Class<?> collectionClass = Reflect.type(loader, "com.soundcloud.android.foundation.api.ApiCollection");
        java.lang.reflect.Constructor<?> constructor = collectionClass.getConstructor(List.class, String.class);
        return Rx.mapObservable(users, list -> {
            try {
                return constructor.newInstance(list, null);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }
}
