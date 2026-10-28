package app.revanced.extension.soundcloud.permissions;

import static app.revanced.extension.soundcloud.permissions.WelcomeLanguage.text;
import static app.revanced.extension.soundcloud.permissions.WelcomeViews.colored;
import static app.revanced.extension.soundcloud.permissions.WelcomeViews.margins;
import static app.revanced.extension.soundcloud.permissions.WelcomeViews.rounded;
import static app.revanced.extension.soundcloud.permissions.WelcomeViews.textView;
import static app.revanced.extension.soundcloud.shared.Colors.contrast;
import static app.revanced.extension.soundcloud.shared.Dimens.dpTruncated;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/** What the greeting shows, and the plainer dialog body in the same style that other Arsound dialogs use. */
final class WelcomeContent {
    private WelcomeContent() {
    }

    /** The Arsound logo and name, the header of Arsound dialogs. */
    static LinearLayout createHeader(Context context) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addLogo(header, context, 40, 12);
        header.addView(textView(context, "Arsound", 22, true));
        return header;
    }

    /** A dialog body in the style of the greeting: logo, then paragraphs. */
    static ScrollView createDialogContent(Context context, CharSequence... paragraphs) {
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dpTruncated(context, 24), dpTruncated(context, 20), dpTruncated(context, 24), dpTruncated(context, 4));
        list.addView(createHeader(context));
        for (CharSequence paragraph : paragraphs) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dpTruncated(context, 16);
            list.addView(textView(context, paragraph, 15, false), params);
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        return scroll;
    }

    /** The logo at the start of a row, if the app has it. */
    private static void addLogo(LinearLayout row, Context context, int sizeDp, int gapDp) {
        int logoId = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_icon");
        if (logoId == 0) return;
        ImageView logo = new ImageView(context);
        logo.setImageResource(logoId);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dpTruncated(context, sizeDp), dpTruncated(context, sizeDp));
        logoParams.rightMargin = dpTruncated(context, gapDp);
        row.addView(logo, logoParams);
    }

    /**
     * The greeting: the logo and a word about the app, where its settings are, then the permissions with what
     * each is for. Colours are set here, from the chosen theme.
     */
    static LinearLayout createGreeting(Context context, List<WelcomeItem> items, boolean askPermissions) {
        int accent = ArsoundTheme.palette(context, "special");
        int primary = ArsoundTheme.palette(context, "primary");
        int secondary = ArsoundTheme.palette(context, "secondary");

        LinearLayout list = new LinearLayout(context);
        list.setTag(ArsoundDialog.KEEP_COLORS);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dpTruncated(context, 22), 0, dpTruncated(context, 22), dpTruncated(context, 4));

        // Logo, then a welcome line over the name.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addLogo(header, context, 52, 14);
        LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(colored(textView(context, text("Добро пожаловать в", "Welcome to"), 13, false), secondary));
        names.addView(colored(textView(context, "Arsound", 24, true), primary));
        header.addView(names);
        list.addView(header);

        list.addView(colored(textView(context, text(
                "Это SoundCloud с дополнениями: без рекламы, со скачиванием треков, прослушиванием без интернета, "
                        + "поиском в YouTube Music и своими темами оформления.",
                "This is SoundCloud with extras: no ads, track downloads, listening without internet, "
                        + "YouTube Music search and its own themes."), 15, false), primary), margins(context, 16));

        // The way to the settings: three numbered steps on a card.
        LinearLayout settings = card(context, primary);
        settings.addView(colored(textView(context, text("Где настройки Arsound", "Where the Arsound settings are"), 16, true), primary));
        String[] steps = {
                text("Откройте вкладку «Библиотека».", "Open the Library tab."),
                text("Нажмите шестерёнку справа сверху.", "Tap the gear in the top right corner."),
                text("Самый верхний пункт — «Arsound». Там всё, что добавляет приложение.",
                        "The topmost item is \"Arsound\". Everything the app adds is there."),
        };
        for (int i = 0; i < steps.length; i++) {
            settings.addView(step(context, i + 1, steps[i], accent, primary), margins(context, 10));
        }
        list.addView(settings, margins(context, 18));

        if (askPermissions) {
            list.addView(colored(textView(context, text("Разрешения", "Permissions"), 16, true), primary), margins(context, 20));
            list.addView(colored(textView(context, text(
                    "Android спросит о каждом отдельно. Любое можно отклонить: приложение продолжит работать, "
                            + "выключится только то, что описано ниже.",
                    "Android asks about each one separately. Any of them can be refused: the app keeps working, "
                            + "only what is described below stops."), 14, false), secondary), margins(context, 4));
            for (WelcomeItem item : items) {
                list.addView(permissionCard(context, item, accent, primary, secondary), margins(context, 10));
            }
        }
        return list;
    }

    /** A permission: its name (with a mark when it is allowed already), what it is for, what stops without it. */
    private static LinearLayout permissionCard(Context context, WelcomeItem item, int accent, int primary, int secondary) {
        LinearLayout permission = card(context, primary);
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(colored(textView(context, item.title, 15, true), primary),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (item.isGranted(context)) {
            TextView granted = colored(textView(context, text("разрешено", "allowed"), 12, true), accent);
            granted.setBackground(rounded(context, (accent & 0x00FFFFFF) | 0x26000000, 100));
            granted.setPadding(dpTruncated(context, 10), dpTruncated(context, 3), dpTruncated(context, 10), dpTruncated(context, 3));
            titleRow.addView(granted);
        }
        permission.addView(titleRow);
        permission.addView(colored(textView(context, text("Зачем: ", "What for: ") + item.purpose, 14, false), secondary),
                margins(context, 6));
        permission.addView(colored(textView(context, text("Без него: ", "Without it: ") + item.withoutIt, 14, false), secondary),
                margins(context, 4));
        return permission;
    }

    /** A numbered step: the number in an accent circle, the text next to it. */
    private static LinearLayout step(Context context, int number, String value, int accent, int primary) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView circle = colored(textView(context, String.valueOf(number), 13, true), contrast(accent));
        circle.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(accent);
        circle.setBackground(shape);
        LinearLayout.LayoutParams circleParams = new LinearLayout.LayoutParams(dpTruncated(context, 26), dpTruncated(context, 26));
        circleParams.rightMargin = dpTruncated(context, 12);
        row.addView(circle, circleParams);
        row.addView(colored(textView(context, value, 14, false), primary),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    /** A soft rounded card, a shade lighter than the dialog. */
    private static LinearLayout card(Context context, int primary) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(context, (primary & 0x00FFFFFF) | 0x10000000, 18));
        card.setPadding(dpTruncated(context, 16), dpTruncated(context, 14), dpTruncated(context, 16), dpTruncated(context, 14));
        return card;
    }
}
