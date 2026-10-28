package app.revanced.extension.soundcloud.settings;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.soundcloud.settings.search.SearchEntry;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.HelpBadge;

import static app.revanced.extension.soundcloud.shared.Lang.text;

/**
 * One row of a settings card: an icon in a tinted tile, a title with a dimmer summary under it, and at the end a
 * switch, a tick, a value or an arrow. This is the base of {@link ActionRow} and {@link ToggleRow}; SettingsUi makes
 * them. A screen changes the summary later through {@link #description()} (counting, loading, status text).
 *
 * <p>While the screens are only read for the search, nothing that costs time (icons, help sheets) is made.
 */
class SettingRow extends LinearLayout implements CardItem {
    private static final int TILE_DP = 36;
    private static final int TILE_GAP_DP = 14;
    /** The value at the end may take this share of the row; a longer one goes under the text. */
    private static final float VALUE_SHARE = 0.35f;

    final SettingsUi ui;
    private final boolean light;
    private final LinearLayout line;
    private final LinearLayout titleLine;
    private final TextView titleView;
    private final SummaryView summaryView;
    private final LinearLayout column;
    private final LinearLayout trailing;
    private ImageView tile;
    private TextView valueView;
    /** True while a long value sits under the summary instead of at the end of the row. */
    private boolean valueBelow;
    private String fullText;
    private boolean hasDetails;
    private Drawable tileIcon;
    private boolean lit = true;
    private boolean danger;
    private SearchEntry searchEntry;

    SettingRow(SettingsUi ui, String title, CharSequence summary) {
        super(ui.activity);
        this.ui = ui;
        light = ui.search.isIndexing();
        setOrientation(VERTICAL);
        setMinimumHeight(ui.dp(56));
        setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        setBackgroundResource(ui.themeAttribute(android.R.attr.selectableItemBackground));

        line = new LinearLayout(ui.activity);
        line.setOrientation(HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        addView(line, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column = new LinearLayout(ui.activity);
        column.setOrientation(VERTICAL);
        line.addView(column, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        titleLine = new LinearLayout(ui.activity);
        titleLine.setOrientation(HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(titleLine);
        titleView = new TextView(ui.activity);
        ui.style(titleView, "BodyLarge.Primary");
        titleView.setText(title);
        titleView.setMaxLines(3);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleLine.addView(titleView, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        summaryView = new SummaryView(ui);
        summaryView.setText(summary);
        LayoutParams summaryParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = ui.dp(2);
        column.addView(summaryView, summaryParams);

        trailing = new LinearLayout(ui.activity);
        trailing.setOrientation(HORIZONTAL);
        trailing.setGravity(Gravity.CENTER_VERTICAL);
        trailing.setVisibility(GONE);
        LayoutParams trailingParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trailingParams.leftMargin = ui.dp(8);
        line.addView(trailing, trailingParams);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        placeValue(MeasureSpec.getSize(widthMeasureSpec));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * Keeps the title and the summary from being squeezed by a long value: a value wider than about a third of the row
     * is moved under the summary (where it can use the whole width), a short one stays at the end.
     */
    private void placeValue(int rowWidth) {
        if (valueView == null || rowWidth <= 0) return;
        int inner = rowWidth - getPaddingLeft() - getPaddingRight() - (tile != null ? ui.dp(TILE_DP + TILE_GAP_DP) : 0);
        float wanted = valueView.getPaint().measureText(valueView.getText().toString());
        boolean below = wanted > inner * VALUE_SHARE;
        if (below == valueBelow) return;
        valueBelow = below;
        ((ViewGroup) valueView.getParent()).removeView(valueView);
        if (below) {
            valueView.setMaxWidth(Integer.MAX_VALUE);
            LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = ui.dp(2);
            column.addView(valueView, params);
        } else {
            valueView.setMaxWidth(ui.dp(150));
            // After the help badge, before the arrow.
            trailing.addView(valueView, hasDetails ? 1 : 0);
        }
        trailing.setVisibility(trailing.getChildCount() == 0 ? GONE : VISIBLE);
    }

    // region What screens use

    /** The title, also what the search takes to a row. */
    TextView title() {
        return titleView;
    }

    /** The summary under the title; a screen changes its text while it counts or loads. Empty text hides it. */
    TextView description() {
        return summaryView;
    }

    /** The line with the title; a help badge may be added after the title. */
    LinearLayout titleLine() {
        return titleLine;
    }

    /** The icon of the row in a tinted tile, by key (see {@link SettingsIcons}). Does nothing for a missing icon. */
    SettingRow icon(String key) {
        if (light || tile != null) return this;
        tileIcon = SettingsIcons.icon(ui.activity, key);
        if (tileIcon == null) return this;
        tile = new ImageView(ui.activity);
        tile.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        tile.setImageDrawable(tileIcon);
        LayoutParams params = new LayoutParams(ui.dp(TILE_DP), ui.dp(TILE_DP));
        params.rightMargin = ui.dp(TILE_GAP_DP);
        line.addView(tile, 0, params);
        paintTile();
        return this;
    }

    /** True if the row has an icon tile; the card's dividers then start after it. */
    boolean hasIcon() {
        return tile != null;
    }

    /**
     * Replaces the automatic summary with a hand-written short one. The whole description stays available behind the
     * "?" at the end of the row, so no text is lost.
     */
    SettingRow brief(String summary) {
        summaryView.setText(summary);
        if (!hasDetails && fullText != null && !fullText.isEmpty() && !fullText.equals(summary)) details(fullText);
        return this;
    }

    /** Remembers the whole description the row was made from, for {@link #brief}. */
    void remember(String description) {
        fullText = description;
    }

    /** Words people use for this row that its text does not say; the search finds the row by them. */
    SettingRow keywords(String words) {
        if (searchEntry != null) searchEntry.addKeywords(words);
        return this;
    }

    /** A "?" at the end of the row that opens the full explanation. */
    SettingRow details(CharSequence... paragraphs) {
        if (light || paragraphs == null || paragraphs.length == 0 || hasDetails) return this;
        hasDetails = true;
        TextView badge = HelpBadge.create(ui.activity, ui.secondary(), text("Подробнее", "More"), paragraphs);
        badge.setOnClickListener(null);
        badge.setClickable(false);
        FrameLayout hit = new FrameLayout(ui.activity);
        hit.setBackgroundResource(ui.themeAttribute(android.R.attr.selectableItemBackgroundBorderless));
        hit.setContentDescription(text("Подробнее", "More"));
        hit.setOnClickListener(v -> HelpBadge.show(ui.activity, paragraphs));
        hit.addView(badge, new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
        trailing.addView(hit, 0, new LayoutParams(ui.dp(48), ui.dp(48)));
        trailing.setVisibility(VISIBLE);
        return this;
    }

    /** The chosen value in the accent colour at the end of the row, followed by an arrow; a second call changes it. */
    SettingRow value(String value) {
        boolean first = valueView == null;
        plainValue(value);
        if (first) chevron();
        return this;
    }

    /** A value in the accent colour at the end of the row, without an arrow (a slider's number). Returns its view. */
    TextView plainValue(String value) {
        if (valueView == null) {
            valueView = new TextView(ui.activity);
            ui.style(valueView, "Body.Primary");
            valueView.setTextColor(ui.accent());
            valueView.setSingleLine(true);
            valueView.setEllipsize(TextUtils.TruncateAt.END);
            valueView.setMaxWidth(ui.dp(150));
            trailing.addView(valueView);
            trailing.setVisibility(VISIBLE);
        }
        valueView.setText(value);
        return valueView;
    }

    /** An arrow at the end: the row opens something. */
    SettingRow chevron() {
        if (light) return this;
        ImageView arrow = new ImageView(ui.activity);
        Drawable drawable = SettingsIcons.icon(ui.activity, "chevron");
        if (drawable == null) drawable = ui.lineIcon(new float[]{.35f, .2f, .65f, .5f, .35f, .8f}, ui.secondary());
        arrow.setImageDrawable(drawable);
        arrow.setImageTintList(ColorStateList.valueOf(ui.secondary()));
        arrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LayoutParams params = new LayoutParams(ui.dp(20), ui.dp(20));
        params.leftMargin = ui.dp(4);
        trailing.addView(arrow, params);
        trailing.setVisibility(VISIBLE);
        return this;
    }

    /** The title and the icon in the colour of errors: for deleting and resetting rows. */
    SettingRow danger() {
        danger = true;
        titleView.setTextColor(ui.error());
        paintTile();
        return this;
    }

    /** The tile is lit in the accent for an enabled feature and neutral for a disabled one. */
    void setLit(boolean lit) {
        this.lit = lit;
        paintTile();
    }

    // endregion

    // region For SettingsUi and the subclasses

    /** The handle to the row's entry in the search index, if the screens are being read for it. */
    void setSearchEntry(SearchEntry entry) {
        searchEntry = entry;
    }

    /** Puts a view at the end of the row, such as a toggle or a tick. */
    void addTrailing(View view, int width, int height) {
        trailing.addView(view, new LayoutParams(width, height));
        trailing.setVisibility(VISIBLE);
    }

    /** Puts a view under the text across the whole row, such as a slider. */
    void addBelow(View view) {
        addView(view, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void paintTile() {
        if (tile == null) return;
        int ink = danger ? ui.error() : lit ? ui.accent() : ui.secondary();
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(ui.dp(10));
        shape.setColor(Colors.withAlpha(ink, lit || danger ? 0x2E : 0x1F));
        tile.setBackground(shape);
        tile.setImageTintList(ColorStateList.valueOf(ink));
    }

    // endregion

    /** The summary: a dimmer, smaller line that is gone when it has no text. */
    private static final class SummaryView extends TextView {
        SummaryView(SettingsUi ui) {
            super(ui.activity);
            ui.style(this, "Body.Secondary");
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            setAlpha(0.8f);
            setVisibility(GONE);
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            setVisibility(text == null || text.length() == 0 ? GONE : VISIBLE);
        }
    }
}
