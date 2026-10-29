package app.revanced.extension.soundcloud.lyrics;

import static app.revanced.extension.soundcloud.shared.Colors.withAlpha;
import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.soundcloud.lyrics.transcribe.AsrModel;
import app.revanced.extension.soundcloud.lyrics.transcribe.Sizes;
import app.revanced.extension.soundcloud.lyrics.transcribe.SongLanguage;
import app.revanced.extension.soundcloud.lyrics.transcribe.TranscriptionManager;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * "What language is the song in?": the first question of every recognition, and of a recognition done again when the
 * answer was wrong. Three cards (Russian, English, another language), each saying which network will listen and what
 * it still has to download; the likely answer is outlined, the language of the text on screen is marked. A tap on a
 * card is the answer.
 * <p>
 * Used by {@link LyricsAi}.
 */
final class SongLanguageDialog {
    /** Hears the answer. */
    interface Answer {
        void chosen(SongLanguage language);
    }

    private final Context context;
    private final int accent;
    private final int primary;
    private final int secondary;
    private Dialog shown;

    private SongLanguageDialog(Context context) {
        this.context = context;
        accent = ArsoundTheme.palette(context, "special");
        primary = ArsoundTheme.palette(context, "primary");
        secondary = ArsoundTheme.palette(context, "secondary");
    }

    /**
     * @param likely  The answer outlined as the likely one; null for none.
     * @param current The language of the text on screen, marked "now"; null on the first recognition.
     */
    static void show(Context context, SongLanguage likely, SongLanguage current, Answer answer) {
        new SongLanguageDialog(context).show(likely, current, answer);
    }

    private void show(SongLanguage likely, SongLanguage current, Answer answer) {
        LinearLayout root = new LinearLayout(context);
        root.setTag(ArsoundDialog.KEEP_COLORS);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), 0, dp(20), dp(4));

        TextView lead = new TextView(context);
        lead.setText(current == null
                ? text("От ответа зависит, какая нейросеть будет слушать песню.",
                "The answer decides which neural network listens to the song.")
                : text("Песня будет распознана заново на выбранном языке.",
                "The song will be recognised again in the chosen language."));
        lead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        lead.setTextColor(secondary);
        root.addView(lead, rowParams(0));

        for (SongLanguage language : SongLanguage.values()) {
            View card = card(language, language == likely, language == current);
            card.setOnClickListener(v -> {
                if (shown != null) shown.dismiss();
                answer.chosen(language);
            });
            root.addView(card, rowParams(10));
        }

        shown = new ArsoundDialog(context)
                .setTitle(current == null ? text("На каком языке поют?", "What language is it sung in?")
                        : text("Не тот язык?", "Wrong language?"))
                .setView(root)
                .setNegativeButton(text("Отмена", "Cancel"), null)
                .show();
    }

    /** One answer: its name, the network behind it and what it still needs, in a rounded card. */
    private View card(SongLanguage language, boolean likely, boolean current) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(13));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(14));
        shape.setColor(likely ? withAlpha(accent, 0x1F) : withAlpha(primary, 0x0F));
        if (likely) shape.setStroke(dp(1.5f), accent);
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 0x33)), shape, null));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(context);
        name.setText(language.label());
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(primary);
        heading.addView(name);
        if (current) heading.addView(badge(text("сейчас", "now")));
        card.addView(heading);

        TextView about = new TextView(context);
        about.setText(about(language));
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        about.setTextColor(secondary);
        about.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams aboutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aboutParams.topMargin = dp(3);
        card.addView(about, aboutParams);
        return card;
    }

    /** Which network listens, and the download it still needs. */
    private static String about(SongLanguage language) {
        String network;
        switch (language) {
            case RUSSIAN:
                network = text("Русская нейросеть GigaAM — точнее всех на русских песнях.",
                        "The Russian network GigaAM — the most accurate on Russian songs.");
                break;
            case ENGLISH:
                network = text("Многоязычная Whisper, настроенная на английский.",
                        "The multilingual Whisper, set to English.");
                break;
            default:
                network = text("Многоязычная Whisper сама определит язык песни.",
                        "The multilingual Whisper finds the language of the song itself.");
                break;
        }
        AsrModel model = language.model();
        long missing = TranscriptionManager.missingBytes(model);
        return missing > 0
                ? network + text(" Один раз нужно скачать " + Sizes.format(missing) + ".",
                " " + Sizes.format(missing) + " to download once.")
                : network;
    }

    private TextView badge(String label) {
        TextView badge = new TextView(context);
        badge.setText(label);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(accent);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(withAlpha(accent, 0x26));
        badge.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(8);
        badge.setLayoutParams(params);
        return badge;
    }

    private LinearLayout.LayoutParams rowParams(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(float value) {
        return Dimens.dp(context, value);
    }
}
