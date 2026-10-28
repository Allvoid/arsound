package app.revanced.extension.soundcloud.local;

import java.io.File;

/**
 * The heart of an imported track, which is liked "as if" while it is among the likes.
 * <p>
 * Called from {@code LikedStatuses.isLiked}, which every track row asks, and from {@code LikeToggler.toggleTrackLike},
 * which the heart calls. A local track is never given to the database, so the server never hears about it:
 * the toggle only adds the file to or removes it from the set of files hidden from the likes.
 */
@SuppressWarnings("unused")
public final class LikeHearts {
    private LikeHearts() {
    }

    /**
     * @param liked What SoundCloud answered.
     * @param urn   The track asked about.
     * @return True also for an imported file that is among the likes.
     */
    public static boolean isFilled(boolean liked, Object urn) {
        if (liked || !LikeObjects.isLocal(urn) || !ImportedLikes.isActive()) return liked;
        File file = LikeObjects.fileOf(urn);
        return file != null && ImportedLikes.likedFiles().contains(file);
    }

    /**
     * @return True if the track is an imported file and the toggle was handled here: nothing may be written for it.
     */
    public static boolean toggle(Object urn, boolean like) {
        if (!LikeObjects.isLocal(urn)) return false;
        File file = LikeObjects.fileOf(urn);
        if (file != null && ImportedLikes.setLiked(file, like)) HeartRefresh.emitAgain();
        return true;
    }

    /**
     * @return True for an imported file: SoundCloud's like tracking (analytics events with the track) must not run, so
     * the file's urn, title and artist never leave the phone.
     */
    public static boolean isLocalTrack(Object urn) {
        return LikeObjects.isLocal(urn);
    }

    /** Called from the constructor of {@code LikesStateProvider}. */
    public static void setStateProvider(Object provider) {
        HeartRefresh.setStateProvider(provider);
    }
}
