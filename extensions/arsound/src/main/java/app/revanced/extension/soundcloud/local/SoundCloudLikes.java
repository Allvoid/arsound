package app.revanced.extension.soundcloud.local;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.soundcloud.shared.Rx;

/**
 * Likes SoundCloud tracks for Arsound's own features, through {@code LikeToggler.toggleTrackLike}, the method the heart
 * calls: the like is written to the database first and sent to the server after, so one that could not be sent stays
 * pending and goes with the next like or sync. Blocks: off the main thread.
 */
public final class SoundCloudLikes {
    private static final String URN = "com.soundcloud.android.foundation.domain.Urn";
    /** A like waits for the server's answer. */
    private static final long ANSWER_SECONDS = 30;

    private static volatile Object toggler;

    private SoundCloudLikes() {
    }

    /** Called from the constructor of {@code LikeToggler}. */
    public static void setToggler(Object instance) {
        toggler = instance;
    }

    /** False until the app has made its {@code LikeToggler}. */
    public static boolean isReady() {
        return toggler != null;
    }

    /**
     * @param urns Urns of tracks, as {@code "soundcloud:tracks:123"}.
     * @return Those that are already liked, or null when the likes cannot be read yet.
     */
    public static Set<String> alreadyLiked(List<String> urns) throws Exception {
        Object statuses = HeartRefresh.latestStatuses();
        if (statuses == null) return null;
        Set<String> liked = new HashSet<>();
        Class<?> urnClass = Class.forName(URN, false, statuses.getClass().getClassLoader());
        Method isLiked = statuses.getClass().getMethod("isLiked", urnClass);
        for (String urn : urns) {
            if (Boolean.TRUE.equals(isLiked.invoke(statuses, trackUrn(urnClass, urn)))) liked.add(urn);
        }
        return liked;
    }

    /**
     * Likes one track, the way a tap on its heart does.
     *
     * @param urn The urn of the track, as {@code "soundcloud:tracks:123"}.
     * @return Null when it went through; what went wrong otherwise.
     */
    public static Throwable like(String urn) throws Exception {
        Object current = toggler;
        if (current == null) return new IllegalStateException("No LikeToggler yet");
        Class<?> urnClass = Class.forName(URN, false, current.getClass().getClassLoader());
        Object answer = current.getClass().getMethod("toggleTrackLike", urnClass, boolean.class)
                .invoke(current, trackUrn(urnClass, urn), true);
        return Rx.awaitCompletable(answer, ANSWER_SECONDS, TimeUnit.SECONDS);
    }

    private static Object trackUrn(Class<?> urnClass, String urn) throws Exception {
        return urnClass.getMethod("forTrack", String.class).invoke(null, urn.substring(urn.lastIndexOf(':') + 1));
    }
}
