package app.revanced.extension.soundcloud.permissions;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.MusicAccess;

/**
 * Greets the user on the first launch and explains every permission before Android asks for it:
 * what it is for, and what stops working without it. Everything can be refused.
 */
public final class WelcomePermissions {
    private static final String PREFERENCES_NAME = "arsound_welcome";
    private static final String SHOWN = "shown";
    private static final String NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    private static boolean shownThisLaunch;

    private WelcomePermissions() {
    }

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    /** True while the greeting is on screen in this launch, so other prompts wait for the next launch. */
    public static boolean isShownThisLaunch() {
        return shownThisLaunch;
    }

    private static final class Item {
        final String permission;
        final String title;
        final String purpose;
        final String withoutIt;

        Item(String permission, String title, String purpose, String withoutIt) {
            this.permission = permission;
            this.title = title;
            this.purpose = purpose;
            this.withoutIt = withoutIt;
        }
    }

    private static List<Item> items() {
        List<Item> items = new ArrayList<>();
        // Before Android 13 notifications need no permission.
        if (Build.VERSION.SDK_INT >= 33) {
            items.add(new Item(NOTIFICATIONS,
                    text("Уведомления", "Notifications"),
                    text("плеер в шторке и на экране блокировки, ход скачивания треков.",
                            "the player in the notification shade and on the lock screen, download progress."),
                    text("музыка играет, но управлять ею из шторки нельзя, а скачивание идёт незаметно.",
                            "music plays, but cannot be controlled from the shade, and downloads run unseen.")));
        }
        items.add(new Item(MusicAccess.permission(),
                text("Музыка и аудио", "Music and audio"),
                text("треки, скачанные до переустановки Arsound, продолжают играть из файлов без интернета.",
                        "tracks downloaded before Arsound was reinstalled keep playing from their files offline."),
                text("новые скачивания работают, а старые после переустановки играют только из сети.",
                        "new downloads work, but older ones only play from the network after a reinstall.")));
        return items;
    }

    private static boolean isGranted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Called when an activity comes to the screen. Shows the greeting once, on the main screen. */
    public static void onActivityResumed(Activity activity) {
        if (shownThisLaunch || !activity.getClass().getName().endsWith(".MainActivity")) return;
        SharedPreferences preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (preferences.getBoolean(SHOWN, false)) return;

        List<Item> items = items();
        List<String> missing = new ArrayList<>();
        for (Item item : items) if (!isGranted(activity, item.permission)) missing.add(item.permission);
        if (missing.isEmpty()) {
            preferences.edit().putBoolean(SHOWN, true).apply();
            return;
        }
        shownThisLaunch = true;

        try {
            new AlertDialog.Builder(activity)
                    .setView(createContent(activity, items))
                    .setCancelable(false)
                    .setNegativeButton(text("Позже", "Later"), (dialog, which) -> preferences.edit().putBoolean(SHOWN, true).apply())
                    .setPositiveButton(text("Продолжить", "Continue"), (dialog, which) -> {
                        preferences.edit().putBoolean(SHOWN, true).apply();
                        // Android shows its own window for each permission in turn.
                        activity.requestPermissions(missing.toArray(new String[0]), 0x4157);
                    })
                    .show();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the welcome", ex);
        }
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }

    private static TextView textView(Context context, CharSequence value, float size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    private static ScrollView createContent(Context context, List<Item> items) {
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 4));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int logoId = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_icon");
        if (logoId != 0) {
            ImageView logo = new ImageView(context);
            logo.setImageResource(logoId);
            LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(context, 40), dp(context, 40));
            logoParams.rightMargin = dp(context, 12);
            header.addView(logo, logoParams);
        }
        header.addView(textView(context, "Arsound", 22, true));
        list.addView(header);

        TextView greeting = textView(context, text(
                "Привет, дорогой пользователь! Чтобы Arsound работал стабильно, мы попросим у вас несколько разрешений. "
                        + "Любое можно отклонить: приложение продолжит работать, выключится только то, что описано ниже.",
                "Hi there! For Arsound to work reliably, we will ask you for a few permissions. "
                        + "Any of them can be refused: the app keeps working, only what is described below stops."), 15, false);
        LinearLayout.LayoutParams greetingParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        greetingParams.topMargin = dp(context, 16);
        list.addView(greeting, greetingParams);

        for (Item item : items) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(context, 16);
            String title = isGranted(context, item.permission)
                    ? item.title + text(" — уже разрешено", " — already allowed")
                    : item.title;
            list.addView(textView(context, title, 16, true), params);
            list.addView(textView(context, text("Для чего: ", "What for: ") + item.purpose, 14, false));
            list.addView(textView(context, text("Если отклонить: ", "If refused: ") + item.withoutIt, 14, false));
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        return scroll;
    }
}
