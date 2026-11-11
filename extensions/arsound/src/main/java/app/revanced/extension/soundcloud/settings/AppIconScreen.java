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
                "Значок обновится через несколько секунд. Некоторые рабочие столы убирают ярлык при смене — тогда добавьте его заново из списка приложений. "
                        + "С иконкой SoundCloud приложение и снаружи выглядит как SoundCloud: облако на заставке при запуске и в значке уведомлений "
                        + "(его же показывает «островок» рядом с часами).",
                "The icon updates in a few seconds. Some launchers remove the home screen shortcut when it changes; add it again from the app list. "
                        + "With one of SoundCloud's icons the app looks like SoundCloud from outside too: the cloud at the start of the app and as "
                        + "the icon of the notifications (which the \"island\" beside the clock shows)."));
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
                // The picture at the start of the app follows the icon: SoundCloud's cloud for one of its icons.
                app.revanced.extension.soundcloud.theme.ArsoundTheme.syncStartScreen(activity);
                Toast.makeText(activity, text("Иконка: ", "Icon: ") + icon.name, Toast.LENGTH_SHORT).show();
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            inRow++;
        }
        if (row != null) fillRow(row, inRow, columns);
        loadIconPictures(cells, ids);
    }

    /**
     * The pictures are made only for the cells on the screen and a screen above and below it, one variant at a time,
     * off the main thread; the cells further away give theirs back. The bitmap variants are large images, and all of
     * them held at once took tens of megabytes while the picker stayed open.
     */
    private void loadIconPictures(List<View> cells, List<String> ids) {
        images.clear();
        for (View cell : cells) images.add((ImageView) ((ViewGroup) cell).getChildAt(0));
        this.ids = ids;
        scrollListener = this::requestBind;
        activity.getWindow().getDecorView().getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        requestBind();
    }

    /** The size the pictures are made in. */
    private int pictureSize() {
        return ui.dp(60);
    }

    private final List<ImageView> images = new ArrayList<>();
    private List<String> ids = new ArrayList<>();
    /** Set when the screen goes: the pictures still to come are not made any more. */
    private volatile boolean left;
    /** The pictures made lately, by variant: scrolling back does not make them again. */
    private final android.util.LruCache<String, Drawable> pictures = new android.util.LruCache<>(48);
    /** The variants waiting for the worker, so a cell is not asked for twice. */
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private final app.revanced.extension.soundcloud.shared.SerialWork worker =
            new app.revanced.extension.soundcloud.shared.SerialWork("arsound-icon-pictures");
    private android.view.ViewTreeObserver.OnScrollChangedListener scrollListener;
    private boolean bindPosted;

    /** Binds once per frame at most: a fling sends many scroll events. */
    private void requestBind() {
        if (bindPosted || left) return;
        bindPosted = true;
        activity.getWindow().getDecorView().postOnAnimation(() -> {
            bindPosted = false;
            bindVisible();
        });
    }

    private void bindVisible() {
        int screen = activity.getWindow().getDecorView().getHeight();
        if (screen == 0) return;
        int[] location = new int[2];
        for (int i = 0; i < images.size(); i++) {
            ImageView image = images.get(i);
            String id = ids.get(i);
            image.getLocationInWindow(location);
            boolean near = image.isAttachedToWindow()
                    && location[1] + image.getHeight() > -screen && location[1] < 2 * screen;
            if (!near) {
                if (image.getDrawable() != null) image.setImageDrawable(null);
                continue;
            }
            if (image.getDrawable() != null) continue;
            Drawable cached = pictures.get(id);
            if (cached != null) {
                image.setImageDrawable(cached);
                continue;
            }
            if (!pending.add(id)) continue;
            int size = pictureSize();
            worker.execute(() -> {
                Drawable picture = left || activity.isDestroyed() ? null : AppIcons.thumbnail(activity, id, size);
                Utils.runOnMainThread(() -> {
                    pending.remove(id);
                    if (picture == null || left) return;
                    pictures.put(id, picture);
                    // Bound now if the cell is still near the screen; otherwise it waits in the cache.
                    requestBind();
                });
            });
        }
    }

    @Override
    void onDestroy() {
        left = true;
        if (scrollListener != null) {
            android.view.ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnScrollChangedListener(scrollListener);
        }
        pictures.evictAll();
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
