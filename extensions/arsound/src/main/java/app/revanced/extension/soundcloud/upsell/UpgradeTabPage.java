package app.revanced.extension.soundcloud.upsell;

import static app.revanced.extension.soundcloud.shared.Dimens.dp;
import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.AppResources;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.wallpaper.WallpaperStore;

/**
 * The Upgrade tab. SoundCloud draws its subscription shop there, which sells only through Google Play: in the
 * mod, installed from elsewhere, it ends in "Oops" every time. The tab shows instead why a subscription cannot
 * be taken here and how to hide the tab.
 */
@SuppressWarnings("unused")
public final class UpgradeTabPage {
    /** Marks the page, so the shop does not start loading its offers under it. */
    private static final String TAG = "arsound_upgrade_tab";

    private UpgradeTabPage() {
    }

    /**
     * Injection point. The start of the shop fragment's {@code onCreateView}.
     *
     * @return The page that replaces the shop, or null to draw the shop if the page could not be built.
     */
    public static View createView(LayoutInflater inflater, ViewGroup container) {
        try {
            return build(inflater.getContext());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not build the Upgrade tab page", ex);
            return null;
        }
    }

    /** Injection point. After {@code super.onViewCreated} of the shop fragment: true skips loading the offers. */
    public static boolean isPage(View view) {
        return view != null && TAG.equals(view.getTag());
    }

    private static View build(Context context) {
        int primary = themeColor(context, "themeColorPrimary", 0xffffffff);
        int secondary = themeColor(context, "themeColorSecondary", 0xff999999);
        int surface = themeColor(context, "themeColorSurface", 0xff121212);
        int accent = ArsoundTheme.palette(context, "special");

        ScrollView scroll = new ScrollView(context);
        scroll.setTag(TAG);
        scroll.setFillViewport(true);
        // The wallpaper is the window's: the page leaves it uncovered, like every main screen.
        scroll.setBackgroundColor(WallpaperStore.isActive() ? android.graphics.Color.TRANSPARENT : surface);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_VERTICAL);
        int side = dp(context, 28);
        column.setPadding(side, dp(context, 48), side, dp(context, 48));
        scroll.addView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = label(context, text("Подписка SoundCloud", "SoundCloud subscription"), 24, primary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);

        column.addView(paragraph(context, text(
                "Эта вкладка нужна, чтобы оформить подписку SoundCloud Go или Go+. Здесь её оформить нельзя: "
                        + "SoundCloud продаёт подписку только через Google Play, а Arsound — мод, установленный "
                        + "не оттуда. Поэтому вместо магазина раньше была ошибка «Oops».",
                "This tab is for taking a SoundCloud Go or Go+ subscription. It cannot be taken here: SoundCloud "
                        + "sells it only through Google Play, and Arsound is a mod installed from elsewhere. That is "
                        + "why the shop used to end in \"Oops\"."), secondary, 16));

        // The names are those of the "Bottom bar" screen and of its Upgrade row (settings/BottomBarScreen): keep them in
        // step. The row is a tick that is on while the tab stays, so hiding the tab means taking the tick away.
        column.addView(paragraph(context, text(
                "Вкладку можно скрыть: Настройки → Arsound → Нижняя панель → снимите галочку «Upgrade (если есть)».",
                "You can hide the tab: Settings → Arsound → Bottom bar → untick \"Upgrade (if any)\"."),
                secondary, 12));

        TextView hide = label(context, text("Скрыть вкладку", "Hide the tab"), 16, Colors.ink(accent, 0.6, 0xFF000000, 0xFFFFFFFF));
        hide.setTypeface(Typeface.DEFAULT_BOLD);
        hide.setGravity(Gravity.CENTER);
        int vertical = dp(context, 14);
        hide.setPadding(side, vertical, side, vertical);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(accent);
        shape.setCornerRadius(dp(context, 28));
        hide.setBackground(shape);
        hide.setOnClickListener(view -> {
            Settings.setHideUpgradeTabEnabled(true);
            Context viewContext = view.getContext();
            Activity activity = viewContext instanceof Activity ? (Activity) viewContext
                    : app.revanced.extension.soundcloud.update.UpdateChecker.resumedActivity();
            if (activity == null) {
                Utils.showToastShort(text("Вкладка скрыта после перезапуска SoundCloud", "The tab is hidden once SoundCloud restarts"));
                return;
            }
            // The bar of tabs is built with the main screen: making the screen again is enough, and unlike a restart
            // of the app it leaves the music and the downloads alone. It is made again on the home tab: made again on
            // this one, it kept showing this page under a bar that had no tab for it any more, with "Home" lit, and a
            // tap on "Home" did not leave the page.
            Utils.showToastShort(text("Вкладка скрыта", "Tab hidden"));
            View home = homeTab(activity);
            if (home != null) home.performClick();
            view.postDelayed(() -> {
                if (!activity.isDestroyed()) activity.recreate();
            }, home == null ? 0 : SWITCH_MS);
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(context, 28);
        buttonParams.gravity = Gravity.START;
        column.addView(hide, buttonParams);

        column.addView(paragraph(context, text(
                "Вернуть её можно там же, поставив галочку обратно.",
                "It comes back by ticking the same row again."), secondary, 12));

        // Arsound itself is free: the place where SoundCloud sold its subscription offers to support the project.
        column.addView(paragraph(context, text(
                "Arsound бесплатный. Если хотите премиум-доступ или просто поддержать проект, это можно сделать в нашем "
                        + "боте в Telegram.",
                "Arsound is free. If you want the premium access or just want to support the project, you can do it in "
                        + "our Telegram bot."), primary, 36));
        TextView donate = label(context, app.revanced.extension.soundcloud.shared.DonateDialog.label(), 16, accent);
        donate.setTypeface(Typeface.DEFAULT_BOLD);
        donate.setGravity(Gravity.CENTER);
        donate.setPadding(side, vertical, side, vertical);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(android.graphics.Color.TRANSPARENT);
        outline.setStroke(dp(context, 2), accent);
        outline.setCornerRadius(dp(context, 28));
        donate.setBackground(outline);
        donate.setOnClickListener(view -> {
            Context viewContext = view.getContext();
            Activity activity = viewContext instanceof Activity ? (Activity) viewContext
                    : app.revanced.extension.soundcloud.update.UpdateChecker.resumedActivity();
            app.revanced.extension.soundcloud.shared.DonateDialog.show(activity);
        });
        LinearLayout.LayoutParams donateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        donateParams.topMargin = dp(context, 16);
        donateParams.gravity = Gravity.START;
        column.addView(donate, donateParams);
        return scroll;
    }

    /** The time the switch to the home tab is given before the screen is made again. */
    private static final long SWITCH_MS = 300;

    /** The first tab of SoundCloud's bar, "Home", or null when the bar cannot be found. */
    private static View homeTab(Activity activity) {
        int id = Utils.getResourceIdentifier(app.revanced.extension.shared.ResourceType.ID, "navigation_control_view");
        View bar = id == 0 ? null : activity.findViewById(id);
        return bar == null ? null : firstTab(bar, 0);
    }

    private static View firstTab(View view, int depth) {
        String name = view.getClass().getName();
        if (name.endsWith("NavigationBarItemView") || name.endsWith("BottomNavigationItemView")) return view;
        if (depth >= 6 || !(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = firstTab(group.getChildAt(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static TextView paragraph(Context context, String value, int color, int topMarginDp) {
        TextView view = label(context, value, 15, color);
        view.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, topMarginDp);
        view.setLayoutParams(params);
        return view;
    }

    private static TextView label(Context context, String value, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        return view;
    }

    /** A colour of the app theme by the name of its attribute, or the fallback if the app has no such attribute. */
    private static int themeColor(Context context, String attribute, int fallback) {
        int id = Utils.getResourceIdentifier(ResourceType.ATTR, attribute);
        if (id == 0) return fallback;
        return AppResources.themeColor(context, id, fallback);
    }
}
