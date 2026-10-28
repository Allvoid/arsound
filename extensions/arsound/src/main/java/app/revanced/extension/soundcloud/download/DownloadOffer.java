package app.revanced.extension.soundcloud.download;

import android.app.Activity;
import android.content.Context;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.shared.Urns;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * What a subscription offer of a SoundCloud download button asks to download. Each kind knows how Arsound downloads
 * the same thing; SoundCloud names the kind in the upsell context of the offer, see {@link #of}.
 */
enum DownloadOffer {
    /** The "Download" item of a track menu, the button of the track page. */
    TRACK("OFFLINE_TRACK", "OFFLINE_TRACK_PLUS") {
        @Override
        boolean download(String urn) {
            String trackId = Urns.trackId(urn);
            if (trackId == null) return false;
            Logger.printInfo(() -> "SoundCloud download button: track " + trackId);
            Context context = Utils.getContext();
            if (context != null) Utils.runOnMainThread(() -> DownloadTrackPatch.downloadFromSoundCloudButton(context, trackId));
            return true;
        }
    },
    /** The "Download" item of a playlist or album menu. */
    PLAYLIST("OFFLINE_PLAYLIST", "OFFLINE_PLAYLIST_PLUS") {
        @Override
        boolean download(String urn) {
            String playlistId = Urns.playlistId(urn);
            if (playlistId == null) {
                // A list made by SoundCloud (a mix, "Related tracks") has no playlist to check.
                showToast(Lang.text("Этот список собирает SoundCloud: скачайте треки по одному из их меню",
                        "SoundCloud makes this list: download its tracks one by one from their menus"));
                return true;
            }
            Logger.printInfo(() -> "SoundCloud download button: playlist " + playlistId);
            Utils.runOnMainThread(() -> {
                Context context = screen();
                if (context != null) DownloadPlaylistPatch.checkPlaylist(context, playlistId);
            });
            return true;
        }
    },
    /** The download button of "Your likes". */
    LIKES("OFFLINE_LIKES") {
        @Override
        boolean download(String urn) {
            Logger.printInfo(() -> "SoundCloud download button: likes");
            Utils.runOnMainThread(() -> {
                Context context = screen();
                if (context != null) DownloadPlaylistPatch.checkLikes(context);
            });
            return true;
        }
    };

    private final String[] upsellNames;

    DownloadOffer(String... upsellNames) {
        this.upsellNames = upsellNames;
    }

    /**
     * Starts the Arsound download of what the offer is for.
     *
     * @param urn The urn found on the offer, or null if it holds none.
     * @return True if the offer is handled, so it must not open; false to leave it to SoundCloud.
     */
    abstract boolean download(String urn);

    /** The kind of offer with this upsell name, or null for any other (the Downloads row, offline settings). */
    static DownloadOffer of(String upsellName) {
        for (DownloadOffer offer : values()) {
            for (String name : offer.upsellNames) if (name.equals(upsellName)) return offer;
        }
        return null;
    }

    /** The screen on top: the playlist check asks in a dialog. */
    private static Context screen() {
        Activity activity = UpdateChecker.resumedActivity();
        if (activity != null && !activity.isFinishing()) return activity;
        return Utils.getContext();
    }

    private static void showToast(String message) {
        Context context = Utils.getContext();
        if (context != null) DownloadTrackPatch.showToast(context, message);
    }
}
