package app.revanced.extension.soundcloud.upsell;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
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

import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

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

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
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
        scroll.setBackgroundColor(surface);

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

        column.addView(paragraph(context, text(
                "Вкладку можно скрыть: Настройки → Arsound → Реклама и подписка → «Скрыть вкладку Upgrade».",
                "You can hide the tab: Settings → Arsound → Ads and subscription → \"Hide Upgrade tab\"."),
                secondary, 12));

        TextView hide = label(context, text("Скрыть вкладку", "Hide the tab"), 16, contrastOn(accent));
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
            Activity activity = viewContext instanceof Activity ? (Activity) viewContext : null;
            Utils.showToastShort(text("Вкладка скрыта, SoundCloud перезапускается", "Tab hidden, SoundCloud restarts"));
            if (activity != null) ArsoundTheme.restartApp(activity);
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(context, 28);
        buttonParams.gravity = Gravity.START;
        column.addView(hide, buttonParams);

        column.addView(paragraph(context, text(
                "Вернуть её можно там же, выключив переключатель.",
                "It comes back by turning the same switch off."), secondary, 12));
        return scroll;
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

    /** Black or white, whichever reads better on the colour. */
    private static int contrastOn(int color) {
        double luminance = (0.299 * ((color >> 16) & 0xff) + 0.587 * ((color >> 8) & 0xff) + 0.114 * (color & 0xff)) / 255;
        return luminance > 0.6 ? 0xff000000 : 0xffffffff;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int themeColor(Context context, String attribute, int fallback) {
        int id = Utils.getResourceIdentifier(ResourceType.ATTR, attribute);
        if (id == 0) return fallback;
        TypedArray array = context.obtainStyledAttributes(new int[]{id});
        try {
            return array.getColor(0, fallback);
        } finally {
            array.recycle();
        }
    }
}
