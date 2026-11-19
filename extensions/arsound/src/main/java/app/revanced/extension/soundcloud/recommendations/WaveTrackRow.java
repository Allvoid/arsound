package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;

/**
 * The "My Wave by this track" row of the track menu ("⋮"): starts My Wave from that track. The dialog is the menu's own.
 * The row's click listener does hold the dialog (it dismisses it), but only as long as the row is in the menu's view
 * tree: the menu releases the row with its views. Nothing here keeps the row or the listener outside the dialog.
 */
final class WaveTrackRow {
    private static final String ROW_TAG = "arsound_wave_by_track_row";

    private WaveTrackRow() {
    }

    /** Adds the row under the items of the menu, above "Not for me". */
    static void add(Dialog dialog, Object trackUrn) {
        try {
            if (!TrackUrn.isTrack(trackUrn)) return;
            View menuItems = dialog.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "menuItems"));
            if (menuItems == null || !(menuItems.getParent() instanceof LinearLayout)) return;
            LinearLayout parent = (LinearLayout) menuItems.getParent();
            View existing = parent.findViewWithTag(ROW_TAG);
            if (existing != null) parent.removeView(existing);

            String urn = TrackUrn.normalize(trackUrn);
            ViewGroup row = DownloadTrackPatch.createMenuRow(dialog.getContext(),
                    text("Моя волна по этому треку", "My Wave by this track"),
                    "ic_actions_star_sparkle", v -> {
                        dialog.dismiss();
                        MyWave.startFromTrack(urn);
                    });
            row.setTag(ROW_TAG);
            parent.addView(row, parent.indexOfChild(menuItems) + 1, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add the My Wave row", ex);
        }
    }
}
