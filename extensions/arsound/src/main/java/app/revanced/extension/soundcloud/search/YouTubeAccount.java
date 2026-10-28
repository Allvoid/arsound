package app.revanced.extension.soundcloud.search;

import java.io.IOException;

import app.arsound.shaded.newpipe.extractor.stream.AudioStream;

/**
 * An optional YouTube Music account for the Arsound search. Anonymous requests cannot get age-restricted
 * tracks; with the account's cookies the player answers as for a signed-in listener.
 * <p>
 * The cookies stay in the app's private storage and go only to youtube.com ({@link YouTubeSession}). NewPipe asks
 * YouTube as the Android app, which ignores cookies, so age-restricted tracks are asked for here as the YouTube Music
 * web player ({@link YouTubeWebPlayer}).
 */
public final class YouTubeAccount {
    private YouTubeAccount() {
    }

    /** No account: the track is age-restricted and needs a signed-in account. */
    public static final class SignInRequiredException extends IOException {
        SignInRequiredException() {
            super("The track is age-restricted: sign in to YouTube Music");
        }

        SignInRequiredException(String message) {
            super(message);
        }
    }

    /**
     * YouTube took the requests of this network for a bot ("Sign in to confirm that you're not a bot"), and refused
     * the web player with BotGuard tokens too. Without an account, signing in may help; with one, another network.
     */
    public static final class BotCheckException extends IOException {
        BotCheckException(String message) {
            super(message);
        }
    }

    public static boolean isSignedIn() {
        return YouTubeSession.isSignedIn();
    }

    /** Saves the session of the sign-in screen. @return False if the browser has no YouTube session. */
    static boolean saveSession() {
        return YouTubeSession.save();
    }

    public static void signOut() {
        YouTubeSession.signOut();
    }

    /**
     * The best audio of an age-restricted track, asked for with the account.
     *
     * @throws SignInRequiredException If no account is signed in.
     */
    static AudioStream audio(String videoId) throws Exception {
        String cookies = YouTubeSession.cookies();
        if (cookies == null) throw new SignInRequiredException();
        return YouTubeWebPlayer.audio(videoId, cookies);
    }

    /**
     * The best audio of a track that NewPipe was refused as a bot: the app clients it asks as carry no proof
     * that a real player is asking. The web player sends BotGuard's tokens, which YouTube often accepts
     * from the same network, and the account is used if there is one.
     */
    static AudioStream botCheckedAudio(String videoId) throws Exception {
        return YouTubeWebPlayer.audio(videoId, YouTubeSession.cookies());
    }
}
