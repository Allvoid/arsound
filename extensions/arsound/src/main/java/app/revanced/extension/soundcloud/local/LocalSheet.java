package app.revanced.extension.soundcloud.local;

import android.app.Dialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;

/**
 * A bottom sheet built like SoundCloud's own menus: the Material bottom sheet of the app,
 * its title style and its action list rows with icons.
 */
public final class LocalSheet {
    public static final class Item {
        final String title;
        final String icon;
        final Runnable action;

        public Item(String title, String icon, Runnable action) {
            this.title = title;
            this.icon = icon;
            this.action = action;
        }
    }

    private LocalSheet() {
    }

    /** Rows built when the sheet opens, and added each time the list is scrolled near its end. */
    private static final int ROWS_PER_STEP = 40;

    /** @return The dialog shown, or null if the sheet could not be shown. */
    public static Dialog show(Context context, String title, String subtitle, List<Item> items) {
        return show(context, title, subtitle, items, null);
    }

    /**
     * @param onCancel Runs when the sheet is closed without choosing an item (swipe, tap outside, back).
     *                 It does not run if the sheet could not be shown: the result is null then.
     * @return The dialog shown, or null if the sheet could not be shown.
     */
    public static Dialog show(Context context, String title, String subtitle, List<Item> items, Runnable onCancel) {
        try {
            Dialog dialog = (Dialog) Class.forName("com.google.android.material.bottomsheet.BottomSheetDialog")
                    .getConstructor(Context.class, int.class)
                    .newInstance(context, 0);

            boolean[] chosen = {false};
            if (onCancel != null) {
                dialog.setOnDismissListener(d -> {
                    if (!chosen[0]) onCancel.run();
                });
            }

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(0, dp(context, 8), 0, dp(context, 24));

            // Drag handle, like SoundCloud's sheets.
            View handle = new View(context);
            handle.setBackgroundColor(themeColor(context, "themeColorSecondary"));
            handle.setAlpha(0.4f);
            LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(context, 32), dp(context, 4));
            handleParams.gravity = Gravity.CENTER_HORIZONTAL;
            handleParams.bottomMargin = dp(context, 12);
            content.addView(handle, handleParams);

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dimen(context, "spacing_m"), dp(context, 4), dimen(context, "spacing_m"), dp(context, 2));
            android.widget.ImageView logo = new android.widget.ImageView(context);
            logo.setImageResource(DownloadTrackPatch.arsoundIcon("ic_actions_playlist"));
            LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
            logoParams.rightMargin = dp(context, 12);
            header.addView(logo, logoParams);
            TextView titleView = text(context, "H3.Primary", title);
            header.addView(titleView);
            content.addView(header);
            if (subtitle != null) {
                TextView subtitleView = text(context, "Body.Secondary", subtitle);
                subtitleView.setPadding(dimen(context, "spacing_m"), 0, dimen(context, "spacing_m"), dp(context, 8));
                content.addView(subtitleView);
            }

            LinearLayout list = new LinearLayout(context);
            list.setOrientation(LinearLayout.VERTICAL);
            // A long list (a whole imported library) is built a part at a time: all rows at once are a long frame
            // on the main thread and a lot of views.
            List<Item> rows = new ArrayList<>(items);
            int[] built = {0};
            Runnable buildNextRows = () -> {
                int end = Math.min(built[0] + ROWS_PER_STEP, rows.size());
                for (; built[0] < end; built[0]++) {
                    Item item = rows.get(built[0]);
                    list.addView(DownloadTrackPatch.createMenuRow(context, item.title, item.icon, v -> {
                        chosen[0] = true;
                        dialog.dismiss();
                        if (item.action != null) item.action.run();
                    }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
            };
            buildNextRows.run();
            ScrollView scroll = new ScrollView(context);
            scroll.addView(list);
            if (rows.size() > ROWS_PER_STEP) {
                scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    // Less than one screen left below: add the next rows before the end is reached.
                    if (built[0] < rows.size() && list.getBottom() - (scrollY + scroll.getHeight()) < scroll.getHeight()) {
                        try {
                            buildNextRows.run();
                        } catch (Exception ex) {
                            // The sheet is open already, so a failing row must not close the app; the list ends here.
                            Logger.printException(() -> "Could not add more rows to the sheet", ex);
                            built[0] = rows.size();
                        }
                    }
                });
            }
            content.addView(scroll);

            content.setBackgroundColor(themeColor(context, "themeColorSurface"));
            dialog.setContentView(content);
            dialog.show();
            return dialog;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the sheet", ex);
            return null;
        }
    }

    public static List<Item> items() {
        return new ArrayList<>();
    }

    private static TextView text(Context context, String style, String value) {
        TextView view = new TextView(context);
        int id = Utils.getResourceIdentifier(ResourceType.STYLE, style);
        if (id != 0) view.setTextAppearance(id);
        view.setText(value);
        return view;
    }

    private static int dimen(Context context, String name) {
        int id = Utils.getResourceIdentifier(ResourceType.DIMEN, name);
        return id == 0 ? dp(context, 16) : context.getResources().getDimensionPixelSize(id);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int themeColor(Context context, String attribute) {
        int id = Utils.getResourceIdentifier(ResourceType.ATTR, attribute);
        if (id == 0) return 0xff121212;
        TypedArray array = context.obtainStyledAttributes(new int[]{id});
        try {
            return array.getColor(0, 0xff121212);
        } finally {
            array.recycle();
        }
    }
}
