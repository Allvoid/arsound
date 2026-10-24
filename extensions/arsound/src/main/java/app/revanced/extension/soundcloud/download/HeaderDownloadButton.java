package app.revanced.extension.soundcloud.download;

import android.app.Activity;
import android.view.View;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.update.UpdateChecker;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/**
 * The round download button at the top left of a playlist, an album and "Your likes". SoundCloud made it download
 * for its own offline mode, which needs a Go+ subscription, so without one it opened the subscription offer. Here it
 * checks the tracks and downloads the available ones with Arsound, like "Check track downloads" in the menu.
 */
@SuppressWarnings("unused")
public final class HeaderDownloadButton {
    private static final Pattern PLAYLIST_URN = Pattern.compile("^soundcloud:playlists:(\\d+)$");

    private static final Function0<Unit> LIKES_TAP = () -> {
        Activity activity = UpdateChecker.resumedActivity();
        if (activity != null) DownloadPlaylistPatch.checkLikes(activity);
        return Unit.INSTANCE;
    };

    private HeaderDownloadButton() {
    }

    /**
     * Injection point. Called after the playlist header set up its buttons.
     *
     * @param actionBar The {@code SocialPlayableActionBar} of the header.
     * @param metadata  The {@code PlaylistDetailsMetadata}, which holds the playlist urn.
     */
    public static void onPlaylistHeaderBound(View actionBar, Object metadata) {
        try {
            String playlistId = playlistId(metadata);
            View button = actionBar.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "download_action"));
            if (button == null) return;
            if (playlistId == null) {
                // A playlist made by SoundCloud ("Related tracks", mixes) has no playlist id to check: SoundCloud's
                // own tap opened the hidden subscription offer, so the button did nothing at all.
                button.setVisibility(View.GONE);
                return;
            }
            // Without a subscription and without its offer SoundCloud hides the button.
            button.setVisibility(View.VISIBLE);
            View badge = actionBar.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "plan_badge"));
            if (badge != null) badge.setVisibility(View.GONE);
            button.setOnClickListener(v -> DownloadPlaylistPatch.checkPlaylist(v.getContext(), playlistId));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not set up the playlist download button", ex);
        }
    }

    /**
     * Injection point. The tap action of the round download button in Compose, which only "Your likes" uses.
     *
     * @return The Arsound check of the liked tracks instead of SoundCloud's offline mode.
     */
    public static Function0<Unit> likesAction(Function0<Unit> original) {
        return LIKES_TAP;
    }

    /** The urn is a field of the metadata; field names are obfuscated, so it is found by its value. */
    private static String playlistId(Object metadata) throws IllegalAccessException {
        for (Field field : metadata.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(metadata);
            if (value == null) continue;
            Matcher matcher = PLAYLIST_URN.matcher(value.toString());
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }
}
