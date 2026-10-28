package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Dimens.dp;
import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/** The window that explains why the vivo player does not show Arsound and gives the command that fixes it. */
final class VivoHintScreen {
    private static final String PREFERENCES_NAME = "arsound_vivo_music_card";
    /** Set by the "Do not show again" box of the hint. */
    private static final String NEVER_SHOW = "never_show";

    private VivoHintScreen() {
    }

    /** Whether the user ticked "Do not show again". */
    static boolean isNeverShow(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getBoolean(NEVER_SHOW, false);
    }

    /**
     * Explains why the player does not show Arsound and gives the command, which copies on a tap.
     *
     * Its "Do not show again" box shows and changes whether the hint opens by itself on start.
     *
     * @param onClosed Runs when the dialog closes, may be null.
     */
    static void show(Activity activity, Runnable onClosed) {
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
                        + "добавить себя туда не может — это делается один раз командой с компьютера. Эта подсказка "
                        + "есть и в настройках: Arsound → Офлайн и воспроизведение → «Плеер в шторке vivo».",
                "The player in the control centre and on the lock screen of vivo phones shows only the apps on its "
                        + "own list. SoundCloud and Arsound are not on it, so the player says \"Not playing\". An app "
                        + "cannot add itself; it takes one command from a computer, once. This hint is also in the "
                        + "settings: Arsound → Offline and playback → \"The vivo control centre player\".")));
        content.addView(paragraph(activity, secondary, text(
                "Нет компьютера — поставьте Arsound для китайских прошивок из Telegram-канала t.me/arsound_git: "
                        + "он уже в списке плеера. Это отдельное приложение, в него нужно заново войти в аккаунт.",
                "No computer? Install Arsound for Chinese firmware from the Telegram channel t.me/arsound_git: it is "
                        + "already on the player's list. It is a separate app, so you sign in to it again.")));
        content.addView(paragraph(activity, secondary, text(
                "1. На телефоне включите «Отладку по USB» в «Для разработчиков» (раздел открывается семью нажатиями "
                        + "на номер версии ПО в «О телефоне»).\n"
                        + "2. Подключите телефон к компьютеру с adb (Android SDK Platform-Tools) и разрешите отладку.\n"
                        + "3. Выполните на компьютере команду (нажмите на неё, чтобы скопировать):",
                "1. On the phone, turn on USB debugging in Developer options (they open after seven taps on the "
                        + "software version in About phone).\n"
                        + "2. Connect the phone to a computer with adb (Android SDK Platform-Tools) and allow debugging.\n"
                        + "3. Run the command on the computer (tap it to copy):")));
        content.addView(command(activity, primary, accent, VivoMusicCard.adbCommand(activity)));
        content.addView(paragraph(activity, secondary, text(
                "Без компьютера — в терминале на самом телефоне через «Отладку по Wi-Fi» (например aShell с Shizuku), "
                        + "без «adb shell» и кавычек:",
                "Without a computer: in a terminal on the phone itself over wireless debugging (aShell with Shizuku, "
                        + "for example), without \"adb shell\" and the quotes:")));
        content.addView(command(activity, primary, accent, VivoMusicCard.shellCommand(activity)));
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
                "adb shell pm revoke " + activity.getPackageName() + " " + VivoMusicCard.PERMISSION));

        SharedPreferences preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        CheckBox box = new CheckBox(activity);
        box.setText(text("Больше не показывать", "Do not show again"));
        box.setTextColor(secondary);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.setButtonTintList(ColorStateList.valueOf(accent));
        box.setChecked(preferences.getBoolean(NEVER_SHOW, false));
        new ArsoundDialog(activity)
                .setTitle(text("Плеер в шторке vivo", "The vivo control centre player"))
                .setView(content)
                .setButtonsStart(box)
                .setPositiveButton(text("Понятно", "Got it"), null)
                .setOnDismissListener(dialog -> {
                    preferences.edit().putBoolean(NEVER_SHOW, box.isChecked()).apply();
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
}
