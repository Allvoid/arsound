package app.revanced.extension.soundcloud.search;

import static app.revanced.extension.soundcloud.shared.Colors.withAlpha;
import static app.revanced.extension.soundcloud.shared.Dimens.dpTruncated;
import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.soundcloud.shared.HelpBadge;

/**
 * The switch under the search field: "SoundCloud" or "Arsound". The Arsound half holds its name and a small "?" that
 * explains the search.
 */
final class SourceToggle {
    /** Marks the bar, so the search screen is known to have the switch. */
    static final String TAG = "arsound_search_switch";

    private static final CharSequence[] HELP = {
            text("Поиск Arsound нужен, чтобы скачивать треки, которые в SoundCloud скачать нельзя: "
                            + "их там нет, они только для подписчиков, это отрывок или трек защищён.",
                    "Arsound search is for downloading tracks that SoundCloud does not let you download: "
                            + "missing ones, subscriber-only ones, previews or protected tracks."),
            text("Треки ищутся в YouTube Music. Нажмите на трек, чтобы послушать и проверить, тот ли это; "
                            + "нажмите ещё раз, чтобы остановить. Кнопка справа скачивает трек.",
                    "Tracks are searched on YouTube Music. Tap a track to listen and check it is the right one; "
                            + "tap again to stop. The button on the right downloads it."),
            text("Скачанное появляется в плейлисте «Импортированные» — оттуда трек можно добавить в любой плейлист.",
                    "Downloads appear in the \"Imported\" playlist, and can be added to any playlist from there.")};

    /** Called when the user picks a half. */
    interface Listener {
        void onSelected(boolean arsound);
    }

    private final Context context;
    private final int textColor;
    private final LinearLayout bar;
    private final TextView soundCloudButton;
    private final TextView arsoundButton;
    private final LinearLayout arsoundSegment;
    private final TextView help;

    SourceToggle(Context context, int textColor, Listener listener) {
        this.context = context;
        this.textColor = textColor;

        bar = new LinearLayout(context);
        bar.setTag(TAG);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // Same side margin as the search field; the help button sits under the cast button.

        LinearLayout toggle = new LinearLayout(context);
        toggle.setOrientation(LinearLayout.HORIZONTAL);
        toggle.setPadding(dpTruncated(context, 3), dpTruncated(context, 3), dpTruncated(context, 3), dpTruncated(context, 3));
        GradientDrawable frame = new GradientDrawable();
        frame.setCornerRadius(dpTruncated(context, 12));
        frame.setColor(withAlpha(textColor, 0x1A));
        toggle.setBackground(frame);
        soundCloudButton = segment("SoundCloud");
        arsoundButton = segment("Arsound");
        toggle.addView(soundCloudButton, new LinearLayout.LayoutParams(0, dpTruncated(context, 38), 1));
        soundCloudButton.setOnClickListener(v -> listener.onSelected(false));

        arsoundSegment = new LinearLayout(context);
        arsoundSegment.setOrientation(LinearLayout.HORIZONTAL);
        arsoundSegment.setGravity(Gravity.CENTER);
        arsoundSegment.setOnClickListener(v -> listener.onSelected(true));
        arsoundSegment.addView(arsoundButton);
        help = HelpBadge.create(context, textColor, text("Что такое поиск Arsound", "What Arsound search is"), HELP);
        arsoundSegment.addView(help, HelpBadge.layoutParams(context));
        toggle.addView(arsoundSegment, new LinearLayout.LayoutParams(0, dpTruncated(context, 38), 1));
        bar.setPadding(dpTruncated(context, 16), dpTruncated(context, 4), dpTruncated(context, 16), dpTruncated(context, 8));
        bar.addView(toggle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    View view() {
        return bar;
    }

    private TextView segment(String label) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    /** Shows which half is selected. */
    void showSelection(boolean arsound) {
        style(soundCloudButton, soundCloudButton, !arsound);
        style(arsoundSegment, arsoundButton, arsound);
        HelpBadge.setColor(help, arsound ? textColor : withAlpha(textColor, 0xB0));
    }

    private void style(View segment, TextView label, boolean selected) {
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dpTruncated(context, 10));
        // A soft tint of the text color: filled solid it was a white bar that dazzled over a dark page and a wallpaper.
        fill.setColor(selected ? withAlpha(textColor, 0x38) : Color.TRANSPARENT);
        segment.setBackground(fill);
        label.setTextColor(selected ? textColor : withAlpha(textColor, 0xB0));
    }
}
