package app.revanced.extension.soundcloud.download;

import android.app.Activity;
import android.content.Context;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * SoundCloud's own download buttons: "Download" in the menu of a track, a playlist or an album, the button on the
 * track page, the round button on top of a playlist and of "Your likes".
 * <p>
 * They download for SoundCloud's offline mode, which needs a Go or Go+ subscription. Without one every button opens
 * the subscription offer, which Arsound hides, so a tap did nothing and people took downloading for broken. With the
 * setting on, the offer is replaced by the Arsound download of the same track, playlist or likes.
 * <p>
 * With a subscription SoundCloud downloads by itself and opens no offer, so its own download keeps working. The
 * setting can be turned off to get the original buttons back entirely.
 */
@SuppressWarnings("unused")
public final class DownloadButtons {
    private static final String UPGRADE_TARGET = "com.soundcloud.android.navigation.NavigationLinks$Target$Upgrade";
    private static final Pattern TRACK_URN = Pattern.compile("^soundcloud:tracks:(\\d+)$");
    private static final Pattern PLAYLIST_URN = Pattern.compile("^soundcloud:playlists:(\\d+)$");

    private DownloadButtons() {
    }

    /**
     * Injection point. The start of SoundCloud's navigator, which opens every screen.
     *
     * @return True if the screen is the subscription offer of a download button, which is replaced by the Arsound
     * download and must not open.
     */
    public static boolean interceptNavigation(Object target) {
        if (target == null || !Settings.isInterceptDownloadButtonsEnabled()
                || !UPGRADE_TARGET.equals(target.getClass().getName())) return false;
        try {
            String upsell = null;
            String urn = null;
            for (Field field : target.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(target);
                if (value instanceof Enum && value.getClass().getName().endsWith(".UpsellContext")) {
                    upsell = ((Enum<?>) value).name();
                } else if (urn == null && value != null && value.toString().startsWith("soundcloud:")) {
                    urn = value.toString();
                }
            }
            return handle(upsell, urn);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not read the subscription offer of a download button", ex);
            return false;
        }
    }

    private static boolean handle(String upsell, String urn) {
        if (upsell == null) return false;
        switch (upsell) {
            case "OFFLINE_TRACK":
            case "OFFLINE_TRACK_PLUS": {
                Matcher track = urn == null ? null : TRACK_URN.matcher(urn);
                if (track == null || !track.find()) return false;
                String trackId = track.group(1);
                Logger.printInfo(() -> "SoundCloud download button: track " + trackId);
                Context context = Utils.getContext();
                if (context != null) Utils.runOnMainThread(() -> DownloadTrackPatch.downloadFromSoundCloudButton(context, trackId));
                return true;
            }
            case "OFFLINE_PLAYLIST":
            case "OFFLINE_PLAYLIST_PLUS": {
                Matcher playlist = urn == null ? null : PLAYLIST_URN.matcher(urn);
                if (playlist == null || !playlist.find()) {
                    // A list made by SoundCloud (a mix, "Related tracks") has no playlist to check.
                    showToast(DownloadTrackPatch.text("Этот список собирает SoundCloud: скачайте треки по одному из их меню",
                            "SoundCloud makes this list: download its tracks one by one from their menus"));
                    return true;
                }
                String playlistId = playlist.group(1);
                Logger.printInfo(() -> "SoundCloud download button: playlist " + playlistId);
                Utils.runOnMainThread(() -> {
                    Context context = screen();
                    if (context != null) DownloadPlaylistPatch.checkPlaylist(context, playlistId);
                });
                return true;
            }
            case "OFFLINE_LIKES":
                Logger.printInfo(() -> "SoundCloud download button: likes");
                Utils.runOnMainThread(() -> {
                    Context context = screen();
                    if (context != null) DownloadPlaylistPatch.checkLikes(context);
                });
                return true;
            default:
                // The Downloads row of the library and SoundCloud's offline settings: not a download of something.
                return false;
        }
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

    /**
     * Injection point. The "Download" item of the track menu shows a Go or Go+ badge when the download needs a
     * subscription; with Arsound behind the item it does not.
     */
    public static boolean showPlanBadge(boolean show) {
        return show && !Settings.isInterceptDownloadButtonsEnabled();
    }
}
