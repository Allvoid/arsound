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

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * SoundCloud's server keeps 500 tracks in a playlist at most. The tracks past that are added on this phone only, as
 * "Add to playlist locally" does, and the user is told so once (until the box "Do not show again" is ticked).
 */
final class PlaylistLimit {
    /** What the server keeps in one playlist. */
    static final int SERVER_LIMIT = 500;
    private static final String HINT = "playlist_limit_hint";

    private PlaylistLimit() {
    }

    /**
     * Takes the tracks past the limit out of a track list that is about to be saved and keeps them on this phone.
     * SoundCloud's "Add to playlist" saves the old tracks first and the new ones last, so the new ones are the overflow.
     *
     * @param tracks The whole list; the overflow is removed from it.
     * @return True if some tracks were moved here.
     */
    static boolean moveOverflowLocally(String playlist, Set<Object> tracks) {
        if (tracks.size() <= SERVER_LIMIT) return false;
        List<Object> overflow = new ArrayList<>();
        int index = 0;
        for (Object urn : tracks) {
            if (index++ >= SERVER_LIMIT) overflow.add(urn);
        }
        List<String> entries = new ArrayList<>();
        for (Object urn : overflow) {
            String entry = LocalAdditions.entryOf(urn);
            if (entry != null) entries.add(entry);
        }
        tracks.removeAll(overflow);
        int added = LocalAdditions.addAll(playlist, entries);
        Logger.printInfo(() -> "Playlist " + playlist + " is over " + SERVER_LIMIT + " tracks: " + added
                + " kept on this phone only");
        if (added > 0) showHint(added);
        return true;
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
