package app.revanced.extension.soundcloud.lyrics;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;

/**
 * What the lyrics page shows for a song without lyrics when the neural network can help: a message, and under it a
 * pill-shaped button, and, when there is advice, a quiet underlined line under them. Only the text, the button and
 * that line take taps. The panel itself is as big as they are and does not
 * take taps, so a tap anywhere else on the page still reaches the cover and pauses or plays: the full-page message
 * of {@link LyricsLayer} stays unclickable for the same reason.
 * <p>
 * Driven by {@link LyricsAi}, which says what each state reads and does.
 */
final class AiPanel extends LinearLayout {
    private final TextView message;
    private final TextView pill;
    private final TextView suggestion;

    AiPanel(Context context, int accent) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClickable(false);
        setVisibility(GONE);

        message = new TextView(context);
        message.setGravity(Gravity.CENTER);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        message.setTextColor(0xCCFFFFFF);
        message.setShadowLayer(dp(8), 0, dp(1.5f), 0x99000000);
        message.setLineSpacing(dp(3), 1f);
        message.setMinHeight(Math.round(dp(48)));
        message.setPadding(Math.round(dp(32)), Math.round(dp(10)), Math.round(dp(32)), Math.round(dp(10)));
        addView(message, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        pill = new TextView(context);
        pill.setGravity(Gravity.CENTER);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setTextColor(0xFFFFFFFF);
        pill.setMinHeight(Math.round(dp(48)));
        pill.setMinWidth(Math.round(dp(160)));
        pill.setPadding(Math.round(dp(24)), Math.round(dp(10)), Math.round(dp(24)), Math.round(dp(10)));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(100));
        shape.setColor(Colors.withAlpha(accent, 0x40));
        shape.setStroke(Math.round(dp(2)), accent);
        pill.setBackground(new RippleDrawable(ColorStateList.valueOf(Colors.withAlpha(accent, 0x55)), shape, null));
        pill.setVisibility(GONE);
        LayoutParams pillParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        pillParams.topMargin = Math.round(dp(4));
        addView(pill, pillParams);

        suggestion = new TextView(context);
        suggestion.setGravity(Gravity.CENTER);
        suggestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        suggestion.setTextColor(accent);
        suggestion.setPaintFlags(suggestion.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        suggestion.setShadowLayer(dp(8), 0, dp(1.5f), 0x99000000);
        suggestion.setMinHeight(Math.round(dp(48)));
        suggestion.setPadding(Math.round(dp(32)), Math.round(dp(12)), Math.round(dp(32)), Math.round(dp(4)));
        suggestion.setVisibility(GONE);
        addView(suggestion, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    private float dp(float value) {
        return Dimens.dpExact(getContext(), value);
    }

    /** A message that taps open something, with a button that does the same or something near it. */
    void show(CharSequence text, Runnable onText, CharSequence buttonLabel, Runnable onButton) {
        message.setText(text);
        setTap(message, onText);
        if (buttonLabel == null) {
            pill.setVisibility(GONE);
            setTap(pill, null);
        } else {
            pill.setText(buttonLabel);
            pill.setVisibility(VISIBLE);
            setTap(pill, onButton);
        }
        suggestion.setVisibility(GONE);
        setTap(suggestion, null);
        setContentDescription(text);
    }

    /** Under the button, a line of advice that taps into a better choice; the next {@link #show} removes it. */
    void suggest(CharSequence text, Runnable action) {
        suggestion.setText(text);
        suggestion.setVisibility(VISIBLE);
        setTap(suggestion, action);
    }

    /** A message alone, that takes no taps. */
    void showNote(CharSequence text) {
        show(text, null, null, null);
    }

    /** Only the progress line; the tap on it is the cancel. */
    void showProgress(CharSequence text, Runnable onCancel) {
        show(text, onCancel, null, null);
    }

    /** Sets a tap handler; the listener first and the flag last, as {@link LyricsLayer#showMessage} explains. */
    private static void setTap(View view, final Runnable action) {
        view.setOnClickListener(action == null ? null : new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        view.setClickable(action != null);
    }
}
