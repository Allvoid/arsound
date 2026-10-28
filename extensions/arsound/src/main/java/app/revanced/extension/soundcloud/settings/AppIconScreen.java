package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.branding.AppIcons;

/** The app icon variants as a grid, grouped; tapping one switches the launcher icon. */
final class AppIconScreen extends SectionScreen {
    static final String ID = "app_icon";

    AppIconScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Иконка приложения", "App icon");
    }

    @Override
    boolean searched() {
        return false;
    }

    @Override
    String iconKey() {
        return "appicon";
    }

    /** The app icon variants as a grid, grouped; tapping one switches the launcher icon. */
    @Override
    void fill(LinearLayout list) {
        TextView hint = ui.createText("Body.Secondary", text(
                "Значок обновится через несколько секунд. Некоторые рабочие столы убирают ярлык при смене — тогда добавьте его заново из списка приложений.",
                "The icon updates in a few seconds. Some launchers remove the home screen shortcut when it changes; add it again from the app list."));
        hint.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), ui.dimen("spacing_s"));
        list.addView(hint);

        String current = AppIcons.current(activity);
        List<View> cells = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        final int columns = 4;
        String group = null;
        LinearLayout row = null;
        int inRow = 0;
        for (AppIcons.Icon icon : AppIcons.all()) {
            if (!icon.group.equals(group)) {
                if (row != null) fillRow(row, inRow, columns);
                group = icon.group;
                list.addView(ui.createSubHeading(group));
                row = null;
            }
            if (row == null || inRow == columns) {
                if (row != null) fillRow(row, inRow, columns);
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(8));
                list.addView(row);
                inRow = 0;
            }
            View cell = createIconCell(icon);
            cells.add(cell);
            ids.add(icon.id);
            cell.setSelected(icon.id.equals(current));
            cell.setOnClickListener(v -> {
                if (!AppIcons.apply(activity, icon.id)) {
                    Toast.makeText(activity, text("Не удалось сменить иконку", "Could not change the icon"), Toast.LENGTH_SHORT).show();
                    return;
                }
                for (int i = 0; i < cells.size(); i++) cells.get(i).setSelected(ids.get(i).equals(icon.id));
                Toast.makeText(activity, text("Иконка: ", "Icon: ") + icon.name, Toast.LENGTH_SHORT).show();
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            inRow++;
        }
        if (row != null) fillRow(row, inRow, columns);
        loadIconPictures(cells, ids);
    }

    /**
     * Puts the pictures into the grid one variant at a time, off the main thread. The bitmap variants are large
     * images: all of them read at once freeze the screen for a while and take over a hundred megabytes.
     */
    private void loadIconPictures(List<View> cells, List<String> ids) {
        int size = ui.dp(60);
        List<ImageView> images = new ArrayList<>();
        for (View cell : cells) images.add((ImageView) ((ViewGroup) cell).getChildAt(0));
        Utils.runOnBackgroundThread(() -> {
            for (int i = 0; i < images.size(); i++) {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                Drawable picture = AppIcons.thumbnail(activity, ids.get(i), size);
                if (picture == null) continue;
                ImageView image = images.get(i);
                Utils.runOnMainThread(() -> image.setImageDrawable(picture));
            }
        });
    }

    /** Empty cells keep the last row of a group aligned with the full rows. */
    private void fillRow(LinearLayout row, int inRow, int columns) {
        for (int i = inRow; i < columns; i++) {
            row.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    private View createIconCell(AppIcons.Icon icon) {
        LinearLayout cell = ui.createColumn();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
        cell.setContentDescription(icon.name);

        // The selected icon gets a ring in the accent colour.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(ui.dp(18));
        ring.setStroke(ui.dp(2), ui.themeColor("themeColorHighlight"));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_selected}, ring);
        background.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(0x22888888), background, null);
        cell.setBackground(ripple);

        // The picture comes later, see loadIconPictures.
        ImageView image = new ImageView(activity);
        cell.addView(image, new LinearLayout.LayoutParams(ui.dp(60), ui.dp(60)));

        TextView name = ui.createText("Body.Secondary", icon.name);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setMaxLines(2);
        name.setPadding(0, ui.dp(4), 0, 0);
        cell.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }
}
