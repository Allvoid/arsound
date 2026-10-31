package app.revanced.extension.soundcloud.shared;

import static app.revanced.extension.soundcloud.shared.Dimens.dp;
import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * "Support Arsound": what the premium access gives, and the way to the bot that takes donations. Opened from the
 * Arsound tab and from the title of the Arsound settings. The bot's name is shown too, with a button that copies it,
 * for a phone where the link does not open Telegram.
 */
public final class DonateDialog {
    /** The bot that takes donations, without the "@". */
    public static final String BOT = "ArapplicationsBot";
    private static final String BOT_URL = "https://t.me/" + BOT;

    private DonateDialog() {
    }

    /** The short label of the buttons that open this dialog. */
    public static String label() {
        return text("Поддержать", "Support");
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        int accent = ArsoundTheme.palette(activity, "special");
        int primary = ArsoundTheme.palette(activity, "primary");
        int secondary = ArsoundTheme.palette(activity, "secondary");

        LinearLayout content = new LinearLayout(activity);
        content.setTag(ArsoundDialog.KEEP_COLORS);
        content.setOrientation(LinearLayout.VERTICAL);
        int side = dp(activity, 24);
        content.setPadding(side, 0, side, dp(activity, 8));

        content.addView(paragraph(activity, primary, text(
                "Если хотите премиум-доступ или просто поддержать проект, задонатить можно в нашем боте в Telegram.",
                "If you want the premium access or just want to support the project, you can donate in our Telegram bot.")));
        content.addView(paragraph(activity, secondary, text(
                "Премиум открывается, когда всего задонатили от 500 ₽ (можно частями):\n"
                        + "• ранние тестовые сборки и новости в закрытом канале;\n"
                        + "• ваши баги и идеи разбираются в первую очередь;\n"
                        + "• свой тег в чате;\n"
                        + "• без подсказок FAQ в чате.\n"
                        + "Любая сумма — уже поддержка, спасибо!",
                "The premium access opens once your donations add up to 500 ₽ (in parts too):\n"
                        + "• early test builds and news in a private channel;\n"
                        + "• your bugs and ideas are looked at first;\n"
                        + "• your own tag in the chat;\n"
                        + "• no FAQ hints in the chat.\n"
                        + "Any amount is a help, thank you!")));
        content.addView(caption(activity, secondary, text("Бот:", "The bot:")));
        content.addView(botRow(activity, primary, accent));

        new ArsoundDialog(activity)
                .setTitle(text("Поддержать Arsound", "Support Arsound"))
                .setView(content)
                .setNegativeButton(text("Закрыть", "Close"), null)
                .setPositiveButton(text("Открыть бота", "Open the bot"),
                        (dialog, which) -> UpdateChecker.openUrl(activity, BOT_URL))
                .show();
    }

    /** "@ArapplicationsBot" and a button that copies it. */
    private static View botRow(Context context, int color, int accent) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padding = dp(context, 10);
        row.setPadding(dp(context, 12), padding / 2, padding / 2, padding / 2);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(context, 12));
        background.setColor((accent & 0x00FFFFFF) | 0x1F000000);
        background.setStroke(Math.max(1, dp(context, 1)), (accent & 0x00FFFFFF) | 0x50000000);
        row.setBackground(background);

        TextView name = new TextView(context);
        name.setText("@" + BOT);
        name.setTextColor(color);
        name.setTypeface(Typeface.MONOSPACE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTextIsSelectable(true);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView copy = new TextView(context);
        copy.setText(text("Копировать", "Copy"));
        copy.setTextColor(Colors.ink(accent, 0.6, 0xFF000000, 0xFFFFFFFF));
        copy.setTypeface(Typeface.DEFAULT_BOLD);
        copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        copy.setGravity(Gravity.CENTER);
        copy.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(accent);
        pill.setCornerRadius(dp(context, 20));
        copy.setBackground(pill);
        copy.setOnClickListener(v -> {
            Utils.setClipboard("@" + BOT);
            Utils.showToastShort(text("Имя бота скопировано", "The bot's name is copied"));
        });
        row.addView(copy);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 4);
        params.bottomMargin = dp(context, 6);
        row.setLayoutParams(params);
        return row;
    }

    private static TextView paragraph(Context context, int color, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setLineSpacing(0, 1.15f);
        view.setPadding(0, dp(context, 4), 0, dp(context, 8));
        return view;
    }

    private static TextView caption(Context context, int color, String value) {
        TextView view = paragraph(context, color, value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setPadding(0, dp(context, 4), 0, 0);
        return view;
    }
}
