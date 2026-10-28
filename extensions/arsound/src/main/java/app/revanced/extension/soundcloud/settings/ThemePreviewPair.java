package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.theme.CustomTheme;

/**
 * The drawing of the app that stays on top of {@link CustomThemeScreen} while its settings scroll below. A theme that
 * is always dark or always light is drawn once; a theme that follows the phone is drawn twice side by side, dark and
 * light, each under its name, so both looks can be judged without a switch.
 */
final class ThemePreviewPair extends LinearLayout {
    private static final float SINGLE_DP = 190, GAP_DP = 12;

    private final ThemePreviewView first;
    private final ThemePreviewView second;
    private final LinearLayout firstColumn;
    private final LinearLayout secondColumn;
    private final TextView firstCaption;
    private final TextView secondCaption;
    private boolean both;

    ThemePreviewPair(Context context, SettingsUi ui) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        first = new ThemePreviewView(context);
        second = new ThemePreviewView(context);
        firstCaption = caption(ui, text("Тёмный вид", "Dark look"));
        secondCaption = caption(ui, text("Светлый вид", "Light look"));
        firstColumn = column(first, firstCaption);
        secondColumn = column(second, secondCaption);
        addView(firstColumn);
        addView(secondColumn);
    }

    private static TextView caption(SettingsUi ui, String label) {
        TextView caption = ui.createText("Body.Secondary", label);
        caption.setGravity(Gravity.CENTER);
        caption.setSingleLine(true);
        caption.setPadding(0, ui.dp(4), 0, 0);
        return caption;
    }

    private LinearLayout column(ThemePreviewView preview, TextView caption) {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        column.addView(preview, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(caption, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return column;
    }

    /**
     * @param theme The theme in the colours being edited.
     * @param mode  One of the modes of {@link CustomTheme}: with the phone's own mode both looks are drawn.
     */
    void show(ArsoundTheme.Theme theme, String mode) {
        both = CustomTheme.MODE_AUTO.equals(mode);
        boolean night = !CustomTheme.MODE_LIGHT.equals(mode);
        first.setTheme(theme, night);
        second.setTheme(theme, false);
        secondColumn.setVisibility(both ? View.VISIBLE : View.GONE);
        firstCaption.setVisibility(both ? View.VISIBLE : View.INVISIBLE);
        secondCaption.setVisibility(both ? View.VISIBLE : View.INVISIBLE);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int gap = Dimens.dp(getContext(), GAP_DP);
        int width = both ? (available - gap) / 2 : Math.min(available, Dimens.dp(getContext(), SINGLE_DP));
        setColumnWidth(firstColumn, width, 0);
        setColumnWidth(secondColumn, width, both ? gap : 0);
        super.onMeasure(widthSpec, heightSpec);
    }

    /** Sets the width in the column's own layout parameters, which the measuring that follows reads at once. */
    private static void setColumnWidth(LinearLayout column, int width, int leftMargin) {
        LayoutParams params = (LayoutParams) column.getLayoutParams();
        params.width = width;
        params.leftMargin = leftMargin;
    }
}
