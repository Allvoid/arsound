package app.revanced.extension.soundcloud.local;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * The playlists that live on this phone only, "Imported". Each is a row in SoundCloud's own database, with a
 * private playlist's fields and a number the server never gives out, and a post of the user: to the library, the playlist
 * screen, shuffle and the play queue it is a playlist like any other, and the tracks are added on this phone as they were for
 * a playlist on the server. Nothing about it reaches SoundCloud: the patch keeps the sync away from these numbers
 * ({@link #isLocal}), so the account stays clean.
 * <p>
 * Making the row reads the account once ({@code /me}): the row names its owner.
 */
public final class LocalPlaylists {
    private static final String API = "https://api-v2.soundcloud.com/";
    private static final String PREFIX = "soundcloud:playlists:";
    /** SoundCloud's numbers are about ten digits long: it will not come here. */
    private static final long FIRST_ID = 900_000_000_000_001L;
    public static final String IMPORTED = PREFIX + FIRST_ID;
    /** The "For you" playlist of an earlier version: only {@link LegacyForYou} uses it, to take it out. */
    public static final String FOR_YOU = PREFIX + (FIRST_ID + 1);
    private static final String STORED_TITLE = "local_playlist_title:";
    private static final long READ_TIMEOUT_SECONDS = 20;

    private static final String DOMAIN = "com.soundcloud.android.foundation.domain.";

    private LocalPlaylists() {
    }

    /** Whether the playlist exists on this phone only. Called by the patch: the sync and the deletion of posts leave it alone. */
    public static boolean isLocal(Object urn) {
        String text = String.valueOf(urn);
        return IMPORTED.equals(text) || FOR_YOU.equals(text);
    }

    /**
     * The sync of posts removes every stored post the server does not list; a playlist of this phone stays out of its sight.
     *
     * @param single {@code Single<List<PostRecord>>}.
     */
    public static Object withoutLocalPosts(Object single) {
        try {
            return Rx.mapSingle(single, value -> {
                List<Object> kept = new ArrayList<>();
                for (Object post : (List<?>) value) {
                    try {
                        if (isLocal(post.getClass().getMethod("getTargetUrn").invoke(post))) continue;
                    } catch (ReflectiveOperationException ex) {
                        Logger.printException(() -> "Could not read a post", ex);
                    }
                    kept.add(post);
                }
                return kept;
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Could not keep the local playlists out of the sync", ex);
            return single;
        }
    }

    /**
     * Makes sure the row of the playlist is in the database, with this title. Blocks; runs on a background thread.
     *
     * @return False when it cannot be made yet (no network the first time): the next start tries again.
     */
    public static boolean ensure(String urn, String title) throws Exception {
        Object operations = SavedPlaylist.playlistOperations();
        SharedPreferences preferences = SavedPlaylist.preferences();
        if (operations == null || preferences == null) return false;
        Object posts = SavedPlaylist.postsStorage();
        if (posts == null) posts = fieldOfType(operations, "PostsStorage");
        if (posts == null) return false;

        boolean posted = isPosted(posts, urn);
        if (posted && title.equals(preferences.getString(STORED_TITLE + urn, null))) return true;

        String[] me = DownloadTrackPatch.apiGet(API + "me");
        if (me[1] == null) {
            Logger.printInfo(() -> "Local playlist " + urn + " not made: the account did not answer, HTTP " + me[0]);
            return false;
        }
        ClassLoader loader = operations.getClass().getClassLoader();
        Object playlist = apiPlaylist(loader, urn, title, new JSONObject(me[1]));
        Object writer = fieldOfType(operations, "PlaylistStorageWriter");
        writer.getClass().getMethod("storePlaylists", Iterable.class).invoke(writer, Collections.singletonList(playlist));
        if (!posted) {
            Class<?> urnClass = Urns.urnClass(loader);
            Object post = Class.forName(DOMAIN + "collections.Post", true, loader)
                    .getConstructor(urnClass, Date.class, String.class)
                    .newInstance(Urns.forPlaylist(loader, Urns.idOf(urn)), new Date(), null);
            posts.getClass().getMethod("store", Iterable.class).invoke(posts, Collections.singletonList(post));
        }
        preferences.edit().putString(STORED_TITLE + urn, title).apply();
        PlaylistOps operationsOf = PlaylistOps.current();
        if (operationsOf != null) operationsOf.notifyUpdated(operationsOf.playlistUrn(urn));
        Logger.printInfo(() -> "Local playlist " + urn + " \"" + title + "\" is in the database");
        return true;
    }

    /** What a server playlist kept on this phone goes to the local one: its added tracks, its order, its cover. */
    public static void move(String from, String to) {
        List<String> entries = AdditionsStore.entries(from);
        if (!entries.isEmpty()) {
            AdditionsStore.setEntries(to, entries);
            AdditionsStore.setEntries(from, Collections.emptyList());
        }
        LocalCovers.movePlaylistCover(from, to);
        Logger.printInfo(() -> "Moved " + entries.size() + " tracks of " + from + " to the local " + to);
    }

    private static boolean isPosted(Object posts, String urn) throws Exception {
        Object posted = Rx.blockingFirst(posts.getClass().getMethod("loadPostedPlaylists", Integer.class).invoke(posts, (Object) null),
                READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (posted instanceof List) {
            for (Object post : (List<?>) posted) {
                if (urn.equals(Urns.textOrNull(post))) return true;
            }
        }
        return false;
    }

    /** The row as the app's own API model. Its fields say private, no tracks; its creator is the account. */
    private static Object apiPlaylist(ClassLoader loader, String urn, String title, JSONObject me) throws Exception {
        Class<?> playlistClass = Class.forName(DOMAIN + "playlists.ApiPlaylist", true, loader);
        Object stats = Class.forName(DOMAIN + "playlists.ApiPlaylist$PlaylistStats", true, loader)
                .getConstructor(int.class, int.class).newInstance(0, 0);
        Class<?> userClass = Class.forName(DOMAIN + "users.ApiUser", true, loader);
        Object related = Class.forName(DOMAIN + "playlists.ApiPlaylist$RelatedResources", true, loader)
                .getConstructor(userClass, stats.getClass()).newInstance(apiUser(userClass, me), stats);
        Object sharing = Enum.valueOf(Class.forName(DOMAIN + "Sharing", true, loader).asSubclass(Enum.class), "PRIVATE");

        Map<Integer, Object> given = new HashMap<>();
        given.put(0, urn);
        given.put(2, title);
        given.put(6, related);
        given.put(8, sharing);
        given.put(9, "");     // permalink_url
        given.put(10, new Date());
        given.put(12, "");    // set_type
        given.put(15, new Date());
        given.put(22, "PLAYLIST");  // playlist_type
        return make(playlistClass, given);
    }

    private static Object apiUser(Class<?> userClass, JSONObject me) throws Exception {
        Map<Integer, Object> given = new HashMap<>();
        given.put(0, me.getString("urn"));
        given.put(1, me.optString("permalink"));
        given.put(2, me.optString("username"));
        // The app keeps the picture as a template of sizes; the row of the account must not lose it.
        given.put(3, me.isNull("avatar_url") ? null : me.optString("avatar_url").replace("-large.", "-{size}."));
        given.put(9, me.optInt("track_count"));
        given.put(10, me.optInt("playlist_count"));
        given.put(11, me.optLong("followers_count"));
        given.put(12, me.optLong("followings_count"));
        return make(userClass, given);
    }

    /** The longest constructor of a model, with `given` by position; what is not given is empty. */
    private static Object make(Class<?> type, Map<Integer, Object> given) throws Exception {
        Constructor<?> longest = null;
        for (Constructor<?> constructor : type.getConstructors()) {
            if (longest == null || constructor.getParameterTypes().length > longest.getParameterTypes().length) longest = constructor;
        }
        if (longest == null) throw new IllegalStateException("No constructor of " + type.getName());
        Class<?>[] parameters = longest.getParameterTypes();
        Object[] arguments = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            if (given.containsKey(i)) arguments[i] = given.get(i);
            else if (parameters[i] == int.class) arguments[i] = 0;
            else if (parameters[i] == long.class) arguments[i] = 0L;
            else if (parameters[i] == boolean.class) arguments[i] = false;
            else if (parameters[i] == Date.class) arguments[i] = new Date();
            else if (parameters[i] == List.class) arguments[i] = Collections.emptyList();
        }
        return longest.newInstance(arguments);
    }

    /** A field by the simple name of its type: the names of the fields are shrunk, those of the classes are not. */
    private static Object fieldOfType(Object owner, String typeName) throws IllegalAccessException {
        for (Field field : owner.getClass().getDeclaredFields()) {
            if (field.getType().getSimpleName().equals(typeName)) {
                field.setAccessible(true);
                return field.get(owner);
            }
        }
        return null;
    }
}
