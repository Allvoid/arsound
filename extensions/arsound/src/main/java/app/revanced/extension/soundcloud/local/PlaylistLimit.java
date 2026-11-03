package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Dimens.dp;
import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Rx;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * SoundCloud's server keeps 500 tracks in a playlist at most. When an edit makes a playlist grow past that, the tracks
 * the edit added past the limit are added on this phone only, as "Add to playlist locally" does, and the user is told
 * so once (until the box "Do not show again" is ticked).
 * <p>
 * An edit that does not make the playlist grow (a removal, a new order) never moves anything: playlists made before the
 * limit can hold more than 500 tracks on the server, and saving such a playlist as it is must not cut its tail off.
 * What counts as added is decided by {@link PlaylistOverflow}.
 */
final class PlaylistLimit {
    /** What the server keeps in one playlist. */
    static final int SERVER_LIMIT = 500;
    private static final String HINT = "playlist_limit_hint";
    /** The wait for the server list: the save waits for it, and the main thread must not wait long. */
    private static final long READ_TIMEOUT_SECONDS = 5;
    private static final long READ_TIMEOUT_MAIN_SECONDS = 1;

    private PlaylistLimit() {
    }

    /**
     * Takes the tracks that do not fit on the server out of a track list that is about to be saved and keeps them on
     * this phone. Only an edit that makes the playlist longer than {@link #SERVER_LIMIT} moves tracks, and only the
     * tracks it added; the server list as it is now (the edit is not stored yet) tells which those are.
     *
     * @param playlistUrn The playlist {@code Urn}.
     * @param tracks      The whole list; the overflow is removed from it.
     * @return True if some tracks were moved here.
     */
    static boolean moveOverflowLocally(Object playlistUrn, Set<Object> tracks) {
        // Not over the limit: no need to read the server list, as for nearly every edit.
        if (tracks.size() <= SERVER_LIMIT) return false;
        try {
            String playlist = String.valueOf(playlistUrn);
            List<Object> before = serverTracks(playlistUrn);
            List<Object> overflow = PlaylistOverflow.beyondLimit(tracks, before, SERVER_LIMIT);
            if (overflow.isEmpty()) return false;
            List<String> entries = new ArrayList<>();
            for (Object urn : overflow) {
                String entry = LocalAdditions.entryOf(urn);
                if (entry != null) entries.add(entry);
            }
            // Kept here first: if that fails, the tracks stay in the list instead of being lost.
            int added = LocalAdditions.addAll(playlist, entries);
            tracks.removeAll(overflow);
            Logger.printInfo(() -> "Playlist " + playlist + " is over " + SERVER_LIMIT + " tracks"
                    + (before == null ? " (the server list could not be read)" : "") + ": " + added
                    + " kept on this phone only");
            if (added > 0) showHint(added);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not keep the tracks past " + SERVER_LIMIT + " on this phone", ex);
            return false;
        }
    }

    /**
     * The tracks the playlist has on the server right now, from SoundCloud's database. The edit is stored only after
     * the hook returns, so this is the list the edit was made from.
     *
     * @return Null if it could not be read in time; then the caller falls back to the position in the list.
     */
    private static List<Object> serverTracks(Object playlistUrn) {
        try {
            PlaylistOps operations = PlaylistOps.current();
            if (operations == null) return null;
            // Own wait instead of PlaylistOps.serverTracks, which gives up after 20 seconds: too long for a save.
            long seconds = Utils.isCurrentlyOnMainThread() ? READ_TIMEOUT_MAIN_SECONDS : READ_TIMEOUT_SECONDS;
            Object stored = Rx.blockingFirst(operations.trackUrns(playlistUrn), seconds, TimeUnit.SECONDS);
            return stored instanceof List ? new ArrayList<>((List<?>) stored) : null;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the server tracks of " + playlistUrn, ex);
            return null;
        }
    }

    private static void showHint(int count) {
        if (!Settings.getBoolean(HINT, true)) return;
        Utils.runOnMainThread(() -> {
            try {
                Activity activity = UpdateChecker.resumedActivity();
                if (activity == null || activity.isFinishing()) return;
                show(activity, count);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not show the playlist limit hint", ex);
            }
        });
    }

    private static void show(Activity activity, int count) {
        int accent = ArsoundTheme.palette(activity, "special");
        int primary = ArsoundTheme.palette(activity, "primary");
        int secondary = ArsoundTheme.palette(activity, "secondary");

        LinearLayout content = new LinearLayout(activity);
        content.setTag(ArsoundDialog.KEEP_COLORS);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 24), 0, dp(activity, 24), dp(activity, 4));
        content.addView(paragraph(activity, primary, 15, text(
                "SoundCloud хранит в плейлисте не больше " + SERVER_LIMIT + " треков.",
                "SoundCloud keeps " + SERVER_LIMIT + " tracks in a playlist at most.")));
        content.addView(paragraph(activity, secondary, 14, text(
                "Остальные Arsound добавил только на этом телефоне (" + count + "): в плейлисте они играют и "
                        + "показываются как обычно, но на других устройствах и на сайте SoundCloud их не будет.",
                "Arsound added the rest on this phone only (" + count + "): they play and show in the playlist as usual, "
                        + "but they will not appear on your other devices or on the SoundCloud site.")));

        CheckBox box = new CheckBox(activity);
        box.setText(text("Больше не показывать", "Do not show again"));
        box.setTextColor(secondary);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.setButtonTintList(ColorStateList.valueOf(accent));

        new ArsoundDialog(activity)
                .setTitle(text("Больше " + SERVER_LIMIT + " треков", "More than " + SERVER_LIMIT + " tracks"))
                .setView(content)
                .setButtonsStart(box)
                .setPositiveButton(text("Окей", "Okay"), (dialog, which) -> {
                    if (box.isChecked()) Settings.putBoolean(HINT, false);
                })
                .show();
    }

    private static TextView paragraph(Activity activity, int color, int sizeSp, CharSequence value) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setPadding(0, dp(activity, 6), 0, dp(activity, 6));
        return view;
    }
}
