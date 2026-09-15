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

    public static void show(Context context, String title, String subtitle, List<Item> items) {
        try {
            Dialog dialog = (Dialog) Class.forName("com.google.android.material.bottomsheet.BottomSheetDialog")
                    .getConstructor(Context.class, int.class)
                    .newInstance(context, 0);

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
            for (Item item : items) {
                list.addView(DownloadTrackPatch.createMenuRow(context, item.title, item.icon, v -> {
                    dialog.dismiss();
                    if (item.action != null) item.action.run();
                }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            ScrollView scroll = new ScrollView(context);
            scroll.addView(list);
            content.addView(scroll);

            content.setBackgroundColor(themeColor(context, "themeColorSurface"));
            dialog.setContentView(content);
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the sheet", ex);
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
