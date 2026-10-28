package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;

/** The "Not for me" row of the track menu. */
final class DislikeMenuRow {
    private static final String ROW_TAG = "arsound_dislike_row";

    private DislikeMenuRow() {
    }

    /** Adds "Not for me" or "Undo not for me" under the items of the menu. */
    static void add(Dialog dialog, Object trackUrn) {
        try {
            View menuItems = dialog.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "menuItems"));
            if (menuItems == null || !(menuItems.getParent() instanceof LinearLayout)) return;
            LinearLayout parent = (LinearLayout) menuItems.getParent();
            View existing = parent.findViewWithTag(ROW_TAG);
            if (existing != null) parent.removeView(existing);

            String urn = TrackUrn.normalize(trackUrn);
            boolean disliked = TrackDislikes.isDisliked(urn);
            Context context = dialog.getContext();
            ViewGroup row = DownloadTrackPatch.createMenuRow(context,
                    disliked ? text("Вернуть в рекомендации", "Show in recommendations again")
                            : text("Не нравится — не рекомендовать", "Not for me: don't recommend"),
                    "ic_actions_close", v -> {
                        dialog.dismiss();
                        if (disliked) {
                            TrackDislikes.remove(urn);
                        } else {
                            TrackDislikes.add(urn);
                        }
                        Toast.makeText(context, disliked
                                ? text("Трек снова может попасть в рекомендации", "The track may be recommended again")
                                : text("Трек больше не появится в рекомендациях и автовоспроизведении",
                                "The track no longer shows up in recommendations and autoplay"), Toast.LENGTH_SHORT).show();
                    });
            row.setTag(ROW_TAG);
            parent.addView(row, parent.indexOfChild(menuItems) + 1, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add the dislike row", ex);
        }
    }
}
