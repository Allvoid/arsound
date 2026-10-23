package app.revanced.extension.soundcloud.player;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The music player in the control centre and on the lock screen of vivo phones with OriginOS ("Origin Player",
 * com.vivo.musicwidgetmix) shows only the apps of its own list of package names. SoundCloud and Arsound are not on
 * it, so the player says "Not playing" while Arsound plays.
 * <p>
 * The list is the secure setting "music_app_white_list" (the player's built-in list when the setting is absent).
 * An app may write secure settings only with WRITE_SECURE_SETTINGS, which only adb can grant. So Arsound shows a
 * command for a computer: it grants the permission, wakes {@link VivoMusicCardReceiver}, which adds Arsound to the list, and
 * restarts the player so it reads the list again. After that Arsound checks on every start that it is still on the
 * list, because a vivo update or cloud config can replace it.
 */
public final class VivoMusicCard {
    private static final String PLAYER_PACKAGE = "com.vivo.musicwidgetmix";
    private static final String LIST_KEY = "music_app_white_list";
    private static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";

    private static final String PREFERENCES_NAME = "arsound_vivo_music_card";
    private static final String ASKED = "asked";

    /**
     * The list of Origin Player 6.2, written together with Arsound when the setting is absent, so the player keeps
     * the apps it had. "Cooperate" apps have their own integration in the player, all of them use the lock screen.
     */
    private static final String BUILT_IN_PACKAGES = "com.android.bbkmusic com.tencent.qqmusic com.ximalaya.ting.android "
            + "com.kugou.android com.netease.cloudmusic bubei.tingshu cn.kuwo.player cmccwm.mobilemusic "
            + "com.kugou.android.lite com.kugou.android.elder app.podcast.cosmos fm.qingting.qtradio "
            + "com.yibasan.lizhifm cn.missevan com.luna.music com.hiby.music com.tencent.blackkey "
            + "com.vivo.newsreader com.tencent.qqmusiclite cn.wenyu.bodian com.tencent.weread com.ting.mp3.android "
            + "com.xs.fm com.xs.fm.lite com.ximalaya.ting.lite com.shinyv.cnr com.ormatch.android.asmr "
            + "com.luojilab.player com.kugou.android.tingshu com.tencent.radio com.audio.tingting com.spotify.music "
            + "com.kugou.viper com.baidu.netdisk com.vivo.agent com.vivo.base.agent com.apple.android.music "
            + "com.dragon.read com.kmxs.reader com.qidian.QDReader com.qq.reader com.jjwxc.reader "
            + "com.jd.app.reader bubei.tingshu.pro io.dushu.fandengreader com.larus.nova com.vivo.screenreader "
            + "com.vivo.ai.gptagent com.psyone.brainmusic com.ifeng.fhdt com.ximalaya.ting.kid com.vivo.ai.copilot "
            + "com.qiyi.video com.ss.android.ugc.aweme com.tencent.qqmusicpad tv.danmaku.bili com.shuqi.controller "
            + "com.salt.music com.tencent.mm";
    private static final String BUILT_IN_COOPERATE = " com.android.bbkmusic com.tencent.qqmusic "
            + "com.ximalaya.ting.android com.kugou.android com.netease.cloudmusic bubei.tingshu cn.kuwo.player "
            + "cmccwm.mobilemusic com.kugou.android.lite com.kugou.android.elder app.podcast.cosmos "
            + "fm.qingting.qtradio com.yibasan.lizhifm com.luna.music com.hiby.music com.tencent.blackkey com.xs.fm "
            + "com.xs.fm.lite com.apple.android.music com.spotify.music cn.missevan cn.wenyu.bodian ";
    private static final String BUILT_IN_NOT_ON_LOCK_SCREEN = " com.tencent.qqmusiclite com.ting.mp3.android "
            + "com.ximalaya.ting.lite com.ormatch.android.asmr com.tencent.radio com.audio.tingting com.baidu.netdisk "
            + "com.vivo.ai.copilot com.larus.nova com.psyone.brainmusic com.ifeng.fhdt com.ximalaya.ting.kid ";

    public enum State {
        /** Not a phone with Origin Player. */
        NOT_VIVO,
        /** Arsound is on the player's list. */
        LISTED,
        /** Arsound is not on the list. */
        NOT_LISTED,
        /** Android did not let Arsound read the list. */
        UNREADABLE,
    }

    private static boolean checkedThisLaunch;

    private VivoMusicCard() {
    }

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    /** True on a phone with Origin Player, or where its list exists. */
    public static boolean isSupported(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PLAYER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ex) {
            try {
                return Settings.Secure.getString(context.getContentResolver(), LIST_KEY) != null;
            } catch (RuntimeException readEx) {
                return false;
            }
        }
    }

    public static boolean canWrite(Context context) {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    public static State state(Context context) {
        if (!isSupported(context)) return State.NOT_VIVO;
        try {
            String list = Settings.Secure.getString(context.getContentResolver(), LIST_KEY);
            return list != null && contains(new JSONArray(list), context.getPackageName())
                    ? State.LISTED : State.NOT_LISTED;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the vivo music player list", ex);
            return State.UNREADABLE;
        }
    }

    private static boolean contains(JSONArray list, String packageName) {
        for (int i = 0; i < list.length(); i++) {
            JSONObject entry = list.optJSONObject(i);
            if (entry != null && packageName.equals(entry.optString("packageName"))) return true;
        }
        return false;
    }

    private static JSONObject entry(String packageName, String appName, boolean cooperate, boolean lockScreen)
            throws org.json.JSONException {
        return new JSONObject()
                .put("packageName", packageName)
                .put("appName", appName)
                .put("isCooperate", cooperate ? 1 : 0)
                .put("showLockScreen", lockScreen ? 1 : 0)
                .put("dataStatus", 0);
    }

    private static JSONArray builtInList() throws org.json.JSONException {
        JSONArray list = new JSONArray();
        for (String packageName : BUILT_IN_PACKAGES.split(" ")) {
            list.put(entry(packageName, "",
                    BUILT_IN_COOPERATE.contains(" " + packageName + " "),
                    !BUILT_IN_NOT_ON_LOCK_SCREEN.contains(" " + packageName + " ")));
        }
        return list;
    }

    /**
     * Adds Arsound to the player's list, keeping every app already on it. Needs WRITE_SECURE_SETTINGS.
     *
     * @return What the list holds now.
     */
    public static State addToList(Context context) {
        if (!isSupported(context)) return State.NOT_VIVO;
        try {
            String current = Settings.Secure.getString(context.getContentResolver(), LIST_KEY);
            JSONArray list = current != null ? new JSONArray(current) : builtInList();
            if (contains(list, context.getPackageName())) return State.LISTED;

            // Not a "cooperate" app: the player controls it through its media session, like any other.
            list.put(entry(context.getPackageName(), "Arsound", false, true));
            Settings.Secure.putString(context.getContentResolver(), LIST_KEY, list.toString());
            Logger.printInfo(() -> "Added Arsound to the vivo music player list"
                    + (current == null ? " (with the built-in list)" : ""));
            return state(context);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add Arsound to the vivo music player list", ex);
            return State.UNREADABLE;
        }
    }

    /** The command for a computer with adb: grants the permission, adds Arsound, restarts the player. */
    public static String adbCommand(Context context) {
        return "adb shell \"" + shellCommand(context) + "\"";
    }

    /** The same command for a terminal on the phone itself, such as aShell with Shizuku. */
    public static String shellCommand(Context context) {
        String packageName = context.getPackageName();
        return "pm grant " + packageName + " " + PERMISSION
                + " && am broadcast -n " + packageName + "/" + VivoMusicCardReceiver.class.getName()
                + " && am force-stop " + PLAYER_PACKAGE;
    }

    /**
     * Called when an activity comes to the screen. Once per launch on the main screen: keeps Arsound on the list
     * when it may write it, and otherwise explains once how to add it.
     */
    public static void onActivityResumed(Activity activity) {
        if (checkedThisLaunch || !activity.getClass().getName().endsWith(".MainActivity")) return;
        checkedThisLaunch = true;
        Context context = activity.getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            if (!isSupported(context)) return;
            if (canWrite(context)) {
                addToList(context);
                return;
            }
            SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (preferences.getBoolean(ASKED, false) || state(context) != State.NOT_LISTED) return;
            Utils.runOnMainThread(() -> {
                // Other prompts of the first launch go first; this one waits for the next launch.
                if (app.revanced.extension.soundcloud.permissions.WelcomePermissions.isShownThisLaunch()
                        || activity.isFinishing()) return;
                preferences.edit().putBoolean(ASKED, true).apply();
                showHelp(activity, null);
            });
        });
    }

    /** A short line for the settings: whether the player sees Arsound. */
    public static String describe(State state) {
        switch (state) {
            case LISTED:
                return text("Arsound в списке плеера. Если плеер его всё равно не показывает — перезагрузите телефон.",
                        "Arsound is on the player's list. If the player still does not show it, restart the phone.");
            case UNREADABLE:
                return text("Не удалось прочитать список плеера. Нажмите, чтобы посмотреть, как добавить Arsound.",
                        "Could not read the player's list. Tap to see how to add Arsound.");
            default:
                return text("Плеер в шторке не видит Arsound. Нажмите, чтобы узнать, как его добавить.",
                        "The control centre player does not see Arsound. Tap to see how to add it.");
        }
    }

    /**
     * Explains why the player does not show Arsound and gives the command, which copies on a tap.
     *
     * @param onClosed Runs when the dialog closes, may be null.
     */
    public static void showHelp(Activity activity, Runnable onClosed) {
        int accent = ArsoundTheme.palette(activity, "special");
        int primary = ArsoundTheme.palette(activity, "primary");
        int secondary = ArsoundTheme.palette(activity, "secondary");

        LinearLayout content = new LinearLayout(activity);
        content.setTag(ArsoundDialog.KEEP_COLORS);
        content.setOrientation(LinearLayout.VERTICAL);
        int side = dp(activity, 24);
        content.setPadding(side, 0, side, dp(activity, 8));

        content.addView(paragraph(activity, secondary, text(
                "Плеер в шторке и на экране блокировки vivo показывает только приложения из своего списка. "
                        + "SoundCloud и Arsound в нём нет, поэтому плеер пишет «Not playing». Само приложение "
                        + "добавить себя туда не может — это делается один раз командой с компьютера.",
                "The player in the control centre and on the lock screen of vivo phones shows only the apps on its "
                        + "own list. SoundCloud and Arsound are not on it, so the player says \"Not playing\". An app "
                        + "cannot add itself; it takes one command from a computer, once.")));
        content.addView(paragraph(activity, secondary, text(
                "1. На телефоне включите «Отладку по USB» в «Для разработчиков» (раздел открывается семью нажатиями "
                        + "на номер версии ПО в «О телефоне»).\n"
                        + "2. Подключите телефон к компьютеру с adb (Android SDK Platform-Tools) и разрешите отладку.\n"
                        + "3. Выполните на компьютере команду (нажмите на неё, чтобы скопировать):",
                "1. On the phone, turn on USB debugging in Developer options (they open after seven taps on the "
                        + "software version in About phone).\n"
                        + "2. Connect the phone to a computer with adb (Android SDK Platform-Tools) and allow debugging.\n"
                        + "3. Run the command on the computer (tap it to copy):")));
        content.addView(command(activity, primary, accent, adbCommand(activity)));
        content.addView(paragraph(activity, secondary, text(
                "Без компьютера — в терминале на самом телефоне через «Отладку по Wi-Fi» (например aShell с Shizuku), "
                        + "без «adb shell» и кавычек:",
                "Without a computer: in a terminal on the phone itself over wireless debugging (aShell with Shizuku, "
                        + "for example), without \"adb shell\" and the quotes:")));
        content.addView(command(activity, primary, accent, shellCommand(activity)));
        content.addView(paragraph(activity, secondary, text(
                "Команда разрешит Arsound менять этот список, добавит его туда и перезапустит плеер. Если обновление "
                        + "vivo сбросит список, Arsound сам вернёт себя при запуске, а плеер увидит его после "
                        + "перезагрузки телефона.",
                "The command lets Arsound change this list, adds it and restarts the player. If a vivo update resets "
                        + "the list, Arsound adds itself back on start, and the player sees it after the phone restarts.")));
        content.addView(paragraph(activity, secondary, text(
                "Это разрешение Android даёт менять и другие системные настройки; Arsound трогает только список "
                        + "плеера. Забрать разрешение (тогда Arsound не сможет вернуть себя в список сам):",
                "This Android permission allows changing other system settings too; Arsound only touches the "
                        + "player's list. To take it back (then Arsound cannot add itself back on its own):")));
        content.addView(command(activity, primary, accent,
                "adb shell pm revoke " + activity.getPackageName() + " " + PERMISSION));

        new ArsoundDialog(activity)
                .setTitle(text("Плеер в шторке vivo", "The vivo control centre player"))
                .setView(content)
                .setPositiveButton(text("Понятно", "Got it"), null)
                .setOnDismissListener(dialog -> {
                    if (onClosed != null) onClosed.run();
                })
                .show();
    }

    private static TextView paragraph(Context context, int color, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setPadding(0, dp(context, 6), 0, dp(context, 6));
        return view;
    }

    private static View command(Context context, int color, int accent, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setTextIsSelectable(false);
        int padding = dp(context, 10);
        view.setPadding(padding, padding, padding, padding);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setCornerRadius(dp(context, 10));
        background.setColor((accent & 0x00FFFFFF) | 0x1F000000);
        background.setStroke(Math.max(1, dp(context, 1)), (accent & 0x00FFFFFF) | 0x50000000);
        view.setBackground(background);
        view.setOnClickListener(v -> {
            Utils.setClipboard(value);
            Utils.showToastShort(text("Команда скопирована", "Command copied"));
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 4);
        params.bottomMargin = dp(context, 6);
        view.setLayoutParams(params);
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
