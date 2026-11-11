package app.revanced.extension.soundcloud.local;

import java.io.File;

import app.revanced.extension.shared.Logger;

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
        // A hook on every heart of every list: a failure here must leave SoundCloud's own answer.
        try {
            if (liked || !LikeObjects.isLocal(urn) || !ImportedLikes.isActive()) return liked;
            File file = LikeObjects.fileOf(urn);
            return file != null && ImportedLikes.likedFiles().contains(file);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not tell whether an imported file is liked", ex);
            return liked;
        }
    }

    /**
     * @return True if the track is an imported file and the toggle was handled here: nothing may be written for it.
     */
    public static boolean toggle(Object urn, boolean like) {
        if (!LikeObjects.isLocal(urn)) {
            // A SoundCloud track: counted for the warning about SoundCloud's limit of likes, and looked at a while after.
            LikeLimit.onToggle(urn, like);
            return false;
        }
        // A hook in the tap on a heart, often on the main thread: a failure must not close the app. The tap then does
        // nothing, and it is still handled here, so nothing is written for the file.
        try {
            // With "Imported tracks in Your likes" off the heart does nothing: the files hidden from the likes stay as
            // the user left them.
            if (!ImportedLikes.isActive()) return true;
            File file = LikeObjects.fileOf(urn);
            if (file != null && ImportedLikes.setLiked(file, like)) HeartRefresh.emitAgain();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not like an imported file", ex);
        }
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
