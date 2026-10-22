package app.revanced.extension.soundcloud.permissions;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.app.LocaleManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.LocaleList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
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
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * Greets the user on the first launch: what the app is, where its settings are, and every permission before
 * Android asks for it — what it is for, and what stops working without it. Everything can be refused.
 */
public final class WelcomePermissions {
    private static final String PREFERENCES_NAME = "arsound_welcome";
    /** "_v2": the greeting with the way to the settings is shown once more to those who saw the old one. */
    private static final String SHOWN = "shown_v2";
    private static final String NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    private static boolean shownThisLaunch;
    /** The language of the greeting on screen, picked with its switch; null when it is not shown. */
    private static Boolean russianShown;

    private WelcomePermissions() {
    }

    private static String text(String russian, String english) {
        boolean inRussian = russianShown != null ? russianShown : "ru".equals(Locale.getDefault().getLanguage());
        return inRussian ? russian : english;
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
                text("слушать без интернета треки, скачанные раньше, если Arsound удаляли и ставили заново. "
                                + "Обычные обновления на это не влияют.",
                        "play offline the tracks you downloaded earlier, if Arsound was removed and installed again. "
                                + "Regular updates do not affect this."),
                text("всё работает, но после удаления и новой установки старые скачанные треки будут играть только через интернет.",
                        "everything works, but after removing and installing again, older downloads only play over the network.")));
        return items;
    }

    private static boolean isGranted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Called when an activity comes to the screen. Shows the greeting once, on the main screen. */
    public static void onActivityResumed(Activity activity) {
        if (revealAfterLanguage && activity.getClass().getName().endsWith(".MainActivity")) {
            revealAfterLanguage = false;
            reveal(activity);
            return;
        }
        if (shownThisLaunch || !activity.getClass().getName().endsWith(".MainActivity")) return;
        SharedPreferences preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (preferences.getBoolean(SHOWN, false)) return;

        List<Item> items = items();
        List<String> missing = new ArrayList<>();
        for (Item item : items) if (!isGranted(activity, item.permission)) missing.add(item.permission);
        shownThisLaunch = true;
        // The greeting starts in English; its switch turns it and then the whole app to Russian.
        russianShown = false;
        boolean askPermissions = !missing.isEmpty();

        try {
            FrameLayout holder = new FrameLayout(activity);
            holder.setTag(ArsoundDialog.KEEP_COLORS);
            holder.addView(createContent(activity, items(), askPermissions));
            Dialog[] shown = new Dialog[1];
            View languageSwitch = createLanguageSwitch(activity, russian -> {
                russianShown = russian;
                // The text fades out, changes and fades back in; the buttons change with it.
                holder.animate().alpha(0f).setDuration(110).withEndAction(() -> {
                    holder.removeAllViews();
                    holder.addView(createContent(activity, items(), askPermissions));
                    if (shown[0] != null) labelButtons(shown[0], askPermissions);
                    holder.animate().alpha(1f).setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
                }).start();
            });

            ArsoundDialog dialog = new ArsoundDialog(activity)
                    .setView(holder)
                    .setButtonsStart(languageSwitch)
                    .setCancelable(false);
            if (!askPermissions) {
                // Everything is allowed already: the greeting only tells where the settings are.
                dialog.setPositiveButton(text("Понятно", "Got it"), (d, which) -> finish(activity, preferences));
            } else {
                dialog.setNegativeButton(text("Позже", "Later"), (d, which) -> finish(activity, preferences))
                        .setPositiveButton(text("Разрешить", "Allow"), (d, which) -> {
                            // Android shows its own window for each permission in turn.
                            activity.requestPermissions(missing.toArray(new String[0]), 0x4157);
                            finish(activity, preferences);
                        });
            }
            shown[0] = dialog.show();
        } catch (Exception ex) {
            russianShown = null;
            Logger.printException(() -> "Could not show the welcome", ex);
        }
    }

    private static void labelButtons(Dialog dialog, boolean askPermissions) {
        TextView positive = ArsoundDialog.getButton(dialog, DialogInterface.BUTTON_POSITIVE);
        if (positive != null) positive.setText(askPermissions ? text("Разрешить", "Allow") : text("Понятно", "Got it"));
        TextView negative = ArsoundDialog.getButton(dialog, DialogInterface.BUTTON_NEGATIVE);
        if (negative != null) negative.setText(text("Позже", "Later"));
    }

    /**
     * The greeting is closed: the language picked on it becomes the app language. Android redraws the screen in it
     * by itself, a moment after the dialog is gone, so the switch does not stutter.
     */
    private static void finish(Activity activity, SharedPreferences preferences) {
        preferences.edit().putBoolean(SHOWN, true).apply();
        boolean russian = Boolean.TRUE.equals(russianShown);
        russianShown = null;
        if (Build.VERSION.SDK_INT < 33) return;
        String language = russian ? "ru" : "en";
        if (language.equals(Locale.getDefault().getLanguage())) return;
        // Android rebuilds the screen for the new language, with a moment of empty window in between. The screen
        // fades into the theme colour first and the new one fades in from it, so the change is one smooth blink.
        View veil = addVeil(activity, 0f);
        if (veil == null) return;
        veil.animate().alpha(1f).setStartDelay(120).setDuration(170).withEndAction(() -> {
            try {
                revealAfterLanguage = true;
                LocaleManager manager = activity.getSystemService(LocaleManager.class);
                manager.setApplicationLocales(LocaleList.forLanguageTags(language));
                Logger.printInfo(() -> "Welcome: app language " + language);
            } catch (Exception ex) {
                revealAfterLanguage = false;
                ((android.view.ViewGroup) veil.getParent()).removeView(veil);
                Logger.printException(() -> "Could not change the app language", ex);
            }
        }).start();
    }

    /** Set while the screen is rebuilt for the language picked on the greeting: the new screen fades in. */
    private static boolean revealAfterLanguage;

    /** A cover of the theme colour over the whole window. */
    private static View addVeil(Activity activity, float alpha) {
        if (!(activity.getWindow().getDecorView() instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup decor = (android.view.ViewGroup) activity.getWindow().getDecorView();
        View veil = new View(activity);
        veil.setBackgroundColor(ArsoundTheme.palette(activity, "surface"));
        veil.setAlpha(alpha);
        // Taps during the blink go nowhere.
        veil.setClickable(true);
        decor.addView(veil, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        return veil;
    }

    private static void reveal(Activity activity) {
        View veil = addVeil(activity, 1f);
        if (veil == null) return;
        veil.animate().alpha(0f).setStartDelay(120).setDuration(280).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> ((android.view.ViewGroup) veil.getParent()).removeView(veil)).start();
    }

    /**
     * "EN | RU", in the look of the SoundCloud / Arsound switch of the search: a soft frame, the chosen half filled
     * with the text colour. The fill slides to the other half.
     */
    private static View createLanguageSwitch(Context context, java.util.function.Consumer<Boolean> onChange) {
        int textColor = ArsoundTheme.palette(context, "primary");
        int half = dp(context, 44), height = dp(context, 32);

        FrameLayout frame = new FrameLayout(context);
        frame.setTag(ArsoundDialog.KEEP_COLORS);
        int inset = dp(context, 3);
        frame.setPadding(inset, inset, inset, inset);
        frame.setBackground(rounded(context, (textColor & 0x00FFFFFF) | 0x1A000000, 11));

        View fill = new View(context);
        fill.setBackground(rounded(context, textColor, 9));
        frame.addView(fill, new FrameLayout.LayoutParams(half, height));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        TextView english = textView(context, "EN", 13, true);
        TextView russian = textView(context, "RU", 13, true);
        for (TextView label : new TextView[]{english, russian}) {
            label.setGravity(Gravity.CENTER);
            labels.addView(label, new LinearLayout.LayoutParams(half, height));
        }
        frame.addView(labels);

        int chosenColor = inverse(textColor), otherColor = (textColor & 0x00FFFFFF) | 0xB0000000;
        english.setTextColor(chosenColor);
        russian.setTextColor(otherColor);
        boolean[] isRussian = {false};
        View.OnClickListener select = v -> {
            boolean toRussian = v == russian;
            if (toRussian == isRussian[0]) return;
            isRussian[0] = toRussian;
            fill.animate().translationX(toRussian ? half : 0).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            fade(english, toRussian ? chosenColor : otherColor, toRussian ? otherColor : chosenColor);
            fade(russian, toRussian ? otherColor : chosenColor, toRussian ? chosenColor : otherColor);
            onChange.accept(toRussian);
        };
        english.setOnClickListener(select);
        russian.setOnClickListener(select);
        return frame;
    }

    private static void fade(TextView label, int from, int to) {
        ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        animator.setDuration(200);
        animator.addUpdateListener(a -> label.setTextColor((int) a.getAnimatedValue()));
        animator.start();
    }

    private static int inverse(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return luminance > 0.5 ? 0xFF121212 : Color.WHITE;
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

    /** The Arsound logo and name, the header of Arsound dialogs. */
    public static LinearLayout createHeader(Context context) {
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
        return header;
    }

    /** A dialog body in the style of the greeting: logo, then paragraphs. */
    public static ScrollView createDialogContent(Context context, CharSequence... paragraphs) {
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 4));
        list.addView(createHeader(context));
        for (CharSequence paragraph : paragraphs) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(context, 16);
            list.addView(textView(context, paragraph, 15, false), params);
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        return scroll;
    }

    /**
     * The greeting: the logo and a word about the app, where its settings are, then the permissions with what
     * each is for. Colours are set here, from the chosen theme.
     */
    private static LinearLayout createContent(Context context, List<Item> items, boolean askPermissions) {
        int accent = ArsoundTheme.palette(context, "special");
        int primary = ArsoundTheme.palette(context, "primary");
        int secondary = ArsoundTheme.palette(context, "secondary");

        LinearLayout list = new LinearLayout(context);
        list.setTag(ArsoundDialog.KEEP_COLORS);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(context, 22), 0, dp(context, 22), dp(context, 4));

        // Logo, then a welcome line over the name.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int logoId = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_icon");
        if (logoId != 0) {
            ImageView logo = new ImageView(context);
            logo.setImageResource(logoId);
            LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(context, 52), dp(context, 52));
            logoParams.rightMargin = dp(context, 14);
            header.addView(logo, logoParams);
        }
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
            for (Item item : items) {
                LinearLayout permission = card(context, primary);
                LinearLayout titleRow = new LinearLayout(context);
                titleRow.setOrientation(LinearLayout.HORIZONTAL);
                titleRow.setGravity(Gravity.CENTER_VERTICAL);
                titleRow.addView(colored(textView(context, item.title, 15, true), primary),
                        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                if (isGranted(context, item.permission)) {
                    TextView granted = colored(textView(context, text("разрешено", "allowed"), 12, true), accent);
                    granted.setBackground(rounded(context, (accent & 0x00FFFFFF) | 0x26000000, 100));
                    granted.setPadding(dp(context, 10), dp(context, 3), dp(context, 10), dp(context, 3));
                    titleRow.addView(granted);
                }
                permission.addView(titleRow);
                permission.addView(colored(textView(context, text("Зачем: ", "What for: ") + item.purpose, 14, false), secondary),
                        margins(context, 6));
                permission.addView(colored(textView(context, text("Без него: ", "Without it: ") + item.withoutIt, 14, false), secondary),
                        margins(context, 4));
                list.addView(permission, margins(context, 10));
            }
        }
        return list;
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
        LinearLayout.LayoutParams circleParams = new LinearLayout.LayoutParams(dp(context, 26), dp(context, 26));
        circleParams.rightMargin = dp(context, 12);
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
        card.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        return card;
    }

    private static GradientDrawable rounded(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(context, radius));
        shape.setColor(color);
        return shape;
    }

    private static LinearLayout.LayoutParams margins(Context context, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, top);
        return params;
    }

    private static TextView colored(TextView view, int color) {
        view.setTextColor(color);
        return view;
    }

    private static int contrast(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return luminance > 0.6 ? 0xFF111111 : 0xFFFFFFFF;
    }
}
