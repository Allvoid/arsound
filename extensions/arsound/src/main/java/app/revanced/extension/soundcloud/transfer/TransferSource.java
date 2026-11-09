package app.revanced.extension.soundcloud.transfer;

import android.content.Context;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A service whose music is transferred to SoundCloud: Yandex Music, VK or YouTube Music. Each tells its links, reads
 * a collection as a list of tracks, and has an optional account with its own lists. The rest of a transfer (the
 * search on SoundCloud, the playlists, the likes, the history) is the same for all of them ({@link TransferJob}).
 */
public abstract class TransferSource {
    /** The service answered, but not with the collection: it is private, removed, or the link is wrong. */
    static final class NotAvailableException extends IOException {
        NotAvailableException(String message) {
            super(message);
        }
    }

    /** The account's access is no longer accepted: sign in again. */
    static final class SignInExpiredException extends IOException {
        SignInExpiredException(String message) {
            super(message);
        }
    }

    /** The service gives nothing without an account (VK). */
    static final class SignInRequiredException extends IOException {
        SignInRequiredException(String message) {
            super(message);
        }
    }

    private static List<TransferSource> all;

    TransferSource() {
    }

    /** The sources in the order they are offered; Yandex Music first, as it was the only one. */
    public static synchronized List<TransferSource> all() {
        if (all == null) {
            all = Collections.unmodifiableList(Arrays.asList(YandexSource.INSTANCE, VkSource.INSTANCE,
                    YouTubeMusicSource.INSTANCE));
        }
        return all;
    }

    /** The source with this {@link #id}, or Yandex Music for one that is not known. */
    public static TransferSource byId(String id) {
        for (TransferSource source : all()) if (source.id().equals(id)) return source;
        return all().get(0);
    }

    /** The link of any source in the text, or null if it holds none. */
    public static TransferLink findLink(String text) {
        if (text == null) return null;
        for (TransferSource source : all()) {
            TransferLink link = source.find(text);
            if (link != null) return link;
        }
        return null;
    }

    /** The link of a {@link TransferLink#key}, or null for a key no source knows. */
    public static TransferLink linkOfKey(String key) {
        if (key == null) return null;
        for (TransferSource source : all()) {
            TransferLink link = source.fromKey(key);
            if (link != null) return link;
        }
        return null;
    }

    /** Stable, for the settings: "yandex", "vk", "ytmusic". */
    public abstract String id();

    /** "Yandex Music". */
    public abstract String name();

    /** "from Yandex Music", as it reads after "Transfer". */
    public abstract String fromName();

    /** The link of this source in the text, or null. Pure: no network. */
    public abstract TransferLink find(String text);

    abstract TransferLink fromKey(String key);

    /** The title and the tracks of a collection. Blocks: call it off the main thread. */
    abstract SourceCollection load(TransferLink link) throws Exception;

    /** Whether even a link is read only with the account (VK shows no music to anyone not signed in). */
    public boolean needsSignInForLinks() {
        return false;
    }

    /** What to tell the user when the service did not give the collection. */
    abstract String notAvailable(TransferLink link);

    // region Account

    public abstract boolean isSignedIn();

    /** The account's name as the service shows it; empty until it is known. */
    public abstract String accountName();

    /** Opens the service's own sign-in page. */
    public abstract void signIn(Context context);

    public abstract void signOut();

    /** "Sign in to Yandex". */
    public abstract String signInTitle();

    /** What signing in gives and where the password goes, under the sign-in row. */
    public abstract String signInNote();

    /** "Sign out of Yandex". */
    public abstract String signOutTitle();

    /**
     * The likes and the playlists of the account, likes first; null if not signed in or the service no longer takes
     * the sign-in. Yandex and VK then forget it; YouTube Music keeps it, as it is the Arsound search's sign-in too, and
     * the screen offers to sign in again. Blocks: call it off the main thread.
     */
    public abstract List<SourceList> library() throws Exception;

    // endregion
}
