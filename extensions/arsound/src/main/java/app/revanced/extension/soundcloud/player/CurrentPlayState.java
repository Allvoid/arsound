package app.revanced.extension.soundcloud.player;

import com.soundcloud.android.playback.session.PlayState;

import app.revanced.extension.shared.Logger;

/**
 * A track's page in the player starts from the play state that is current when the page is bound.
 * <p>
 * SoundCloud makes the data of a track's page when the track's data arrives, with the play state of that moment, and
 * binds the page once the account and follow data are there as well, often most of a second later. When playback
 * starts at once (a downloaded file, a cached stream), the player is already playing by then, but the page is bound
 * as buffering, and nothing corrects it: the pages hear only of changes of the state, and there are none until the
 * track ends. The waveform of a buffering track does not run: it jumps to the new position twice a second. Which
 * track it hit depended on how fast its data came, so it looked random.
 */
@SuppressWarnings("unused")
public final class CurrentPlayState {
    /** The newest state of the media session, position updates included. */
    private static volatile PlayState latest;

    private CurrentPlayState() {
    }

    /** Injection point. The start of the method that takes every state of the media session. */
    public static void onState(PlayState state) {
        latest = state;
    }

    /** Whether the player plays or is about to (buffering), as the media session said last. */
    public static boolean isPlaying() {
        PlayState now = latest;
        return now != null && (now.a() || now.h());
    }

    /**
     * Injection point. The play state a track's page is bound with.
     *
     * @return The newest state of the session if it is about the same track, else the given one.
     */
    public static PlayState forPage(PlayState bound) {
        PlayState now = latest;
        // A state without a track (null) is never "the same track": the hook runs while a page is bound, where an
        // exception would close the app.
        if (bound == null || now == null || now == bound || now.o() == null || !now.o().equals(bound.o())) return bound;
        if (now.a() != bound.a() || now.h() != bound.h()) {
            Logger.printInfo(() -> "Player page: bound " + kind(bound) + ", the player is " + kind(now));
        }
        return now;
    }

    private static String kind(PlayState state) {
        return state.a() ? "playing" : state.h() ? "buffering" : "not playing";
    }
}
