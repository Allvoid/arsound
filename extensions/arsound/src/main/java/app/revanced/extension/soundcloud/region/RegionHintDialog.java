package app.revanced.extension.soundcloud.region;

import static app.revanced.extension.soundcloud.shared.Dimens.dp;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * Explains "Not available in your country" and what to do about it, in the look of the chosen theme. The tick
 * "Don't show again" is acted on when the dialog is closed with OK.
 */
final class RegionHintDialog {
    private RegionHintDialog() {
    }

    /** A view of a Compose screen is given the screen's context, which may wrap the activity. */
    private static Context windowContext(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return current;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return context;
    }

    static void show(Context context) {
        try {
            Context host = windowContext(context);
            boolean russian = Lang.russian();
            int accent = ArsoundTheme.palette(host, "special");
            int primary = ArsoundTheme.palette(host, "primary");
            int secondary = ArsoundTheme.palette(host, "secondary");

            LinearLayout content = new LinearLayout(host);
            // The text colours are set here, from the theme.
            content.setTag(ArsoundDialog.KEEP_COLORS);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(host, 24), 0, dp(host, 24), 0);
            for (RegionHintText.Paragraph paragraph : RegionHintText.paragraphs(russian)) {
                content.addView(paragraph(host, paragraph, primary, secondary));
            }
            TextView note = text(host, "Body.Secondary", RegionHintText.bringBack(russian), secondary, 13);
            note.setPadding(0, dp(host, 6), 0, dp(host, 6));
            content.addView(note);

            CheckBox box = new CheckBox(host);
            box.setText(RegionHintText.dontShowAgain(russian));
            box.setTextColor(secondary);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            box.setButtonTintList(ColorStateList.valueOf(accent));

            new ArsoundDialog(host)
                    .setTitle(RegionHintText.title(russian))
                    .setView(content)
                    .setButtonsStart(box)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        if (box.isChecked()) RegionHint.turnOff(russian);
                    })
                    .show();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the region hint", ex);
        }
    }

    private static TextView paragraph(Context context, RegionHintText.Paragraph paragraph, int primary, int secondary) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (!paragraph.lead.isEmpty()) {
            text.append(paragraph.lead).append(' ');
            text.setSpan(new StyleSpan(Typeface.BOLD), 0, paragraph.lead.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text.append(paragraph.body);
        TextView view = text(context, "Body.Secondary", text, secondary, 14);
        view.setPadding(0, dp(context, 6), 0, dp(context, 6));
        if (!paragraph.lead.isEmpty()) {
            // The lead-in is bold and in the main text colour; the rest stays in the quiet one.
            text.setSpan(new android.text.style.ForegroundColorSpan(primary), 0, paragraph.lead.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(text);
        }
        return view;
    }

    private static TextView text(Context context, String style, CharSequence value, int color, int sizeSp) {
        TextView view = new TextView(context);
        int id = Utils.getResourceIdentifier(ResourceType.STYLE, style);
        if (id != 0) view.setTextAppearance(id);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        return view;
    }
}
