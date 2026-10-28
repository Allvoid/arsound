package app.revanced.extension.soundcloud.local;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.function.Consumer;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;

/**
 * Rows that Arsound adds to SoundCloud's bottom-sheet menus of a track and of a playlist. The row itself is made
 * like SoundCloud's own action list items; this class makes the rows close the menu first and puts them in place.
 */
final class MenuRows {
    private MenuRows() {
    }

    /** The menu of a track: its list of items and the container the list lies in, where extra rows are placed beside it. */
    static final class TrackMenu {
        private final View items;
        private final LinearLayout container;

        private TrackMenu(View items, LinearLayout container) {
            this.items = items;
            this.container = container;
        }

        /** @return The menu, or null if the dialog is not a track menu. */
        static TrackMenu find(Dialog dialog) {
            View items = dialog.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "menuItems"));
            if (items == null || !(items.getParent() instanceof LinearLayout)) return null;
            return new TrackMenu(items, (LinearLayout) items.getParent());
        }

        /** Removes the row an earlier call put in, found by its tag: the menu is rebuilt while it is open. */
        void removeTagged(String tag) {
            MenuRows.removeTagged(container, tag);
        }

        /** Adds the row right below the list of items. */
        void addBelowItems(ViewGroup row) {
            insertAfter(container, items, row);
        }

        /** Adds the row right after another row. */
        void addAfter(ViewGroup previous, ViewGroup row) {
            insertAfter(container, previous, row);
        }
    }

    /** The list of the playlist menu, or null if the dialog is not a playlist menu. */
    static LinearLayout playlistMenuItems(Dialog dialog) {
        return dialog.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "playlistBottomSheetMenuItems"));
    }

    /**
     * A row that closes the menu and then opens a screen: the screen needs an activity, and the dialog has one only
     * when it was opened from an activity.
     *
     * @param opener Gets the activity, or the dialog's context if it has none.
     */
    static ViewGroup opening(Dialog dialog, String label, String iconName, Consumer<Context> opener) {
        Context context = dialog.getContext();
        return DownloadTrackPatch.createMenuRow(context, label, iconName, v -> {
            Context activity = dialog.getOwnerActivity() != null ? dialog.getOwnerActivity() : context;
            dialog.dismiss();
            opener.accept(activity);
        });
    }

    /** A row that closes the menu and then runs the action. */
    static ViewGroup running(Dialog dialog, String label, String iconName, Runnable action) {
        return DownloadTrackPatch.createMenuRow(dialog.getContext(), label, iconName, v -> {
            dialog.dismiss();
            action.run();
        });
    }

    /** Adds the row at the end of the list. */
    static void append(LinearLayout list, ViewGroup row) {
        list.addView(row, layoutParams());
    }

    /** Adds the row right after another view of the same container. */
    static void insertAfter(LinearLayout container, View previous, ViewGroup row) {
        container.addView(row, container.indexOfChild(previous) + 1, layoutParams());
    }

    /** Removes the row an earlier call put in, found by its tag: the menu is rebuilt while it is open. */
    static void removeTagged(LinearLayout container, String tag) {
        View existing = container.findViewWithTag(tag);
        if (existing != null) container.removeView(existing);
    }

    private static LinearLayout.LayoutParams layoutParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
