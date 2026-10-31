package app.revanced.extension.soundcloud.search;

import static app.revanced.extension.soundcloud.shared.Dimens.dpTruncated;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The look of the Arsound search, taken from SoundCloud's own search cells so the two halves of the switch read as
 * one app: a 64 dp picture (rounded square for tracks and albums, a circle for artists) at 16 dp from the edge, the
 * title in SoundCloud's H4 style, the artist in its Body style, the details in its Captions style, 80 dp rows.
 */
final class SearchLook {
    /** The picture of a row, as SoundCloud's small cells have it. */
    static final int ART_DP = 64;
    /** The picture at the top of an album or an artist page. */
    static final int HERO_DP = 168;

    final Context context;
    final int primary;
    final int secondary;
    final int accent;
    private final int placeholder;
    private final float density;

    SearchLook(Context context, int textColor) {
        this.context = context;
        this.primary = textColor;
        int themed = ArsoundTheme.palette(context, "secondary");
        this.secondary = themed != 0 ? themed : Colors.withAlpha(textColor, 0xA0);
        this.accent = SearchScreen.accent(context);
        this.placeholder = ArsoundTheme.palette(context, "highlight");
        this.density = context.getResources().getDisplayMetrics().density;
    }

    int dp(int value) {
        return dpTruncated(context, value);
    }

    // region Text

    /** A text in one of SoundCloud's text styles, in the given colour; the size is the fallback if the style is missing. */
    private TextView text(String style, float fallbackSp, int color, CharSequence value) {
        TextView view = new TextView(context);
        int id = Utils.getResourceIdentifier(ResourceType.STYLE, style);
        if (id != 0) view.setTextAppearance(id);
        else view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fallbackSp);
        view.setTextColor(color);
        view.setText(value);
        return view;
    }

    private static TextView oneLine(TextView view) {
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    TextView title(CharSequence value) {
        return oneLine(text("H4.Primary", 14, primary, value));
    }

    TextView subtitle(CharSequence value) {
        return oneLine(text("Body.Secondary", 14, secondary, value));
    }

    TextView meta(CharSequence value) {
        return oneLine(text("Captions.Secondary", 12, secondary, value));
    }

    /** The title of a group of results ("Tracks", "Artists"), as SoundCloud's search sections. */
    TextView header(String value) {
        TextView view = text("H3.Primary", 18, primary, value);
        view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setPadding(dp(16), dp(20), dp(16), dp(8));
        return view;
    }

    TextView note(String value) {
        TextView view = text("Body.Secondary", 15, secondary, value);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        return view;
    }

    /** Title, artist and details stacked, for the middle of a row. */
    LinearLayout texts(TextView title, TextView subtitle, TextView meta) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(title);
        if (subtitle != null) column.addView(subtitle);
        if (meta != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(2);
            column.addView(meta, params);
        }
        return column;
    }

    // endregion

    // region Rows

    /** A row of the list: 80 dp, the picture at 16 dp from the edge, a ripple. */
    LinearLayout row() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinimumHeight(dp(80));
        view.setPadding(dp(16), dp(8), dp(4), dp(8));
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        view.setBackgroundResource(ripple.resourceId);
        return view;
    }

    /** Puts the picture, the texts and an optional view at the end into a row. */
    void fill(LinearLayout row, ImageView art, View texts, View end) {
        row.addView(art, new LinearLayout.LayoutParams(dp(ART_DP), dp(ART_DP)));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.leftMargin = dp(12);
        textParams.rightMargin = dp(4);
        row.addView(texts, textParams);
        if (end != null) row.addView(end);
    }

    /** The arrow at the end of a row that opens a page. */
    ImageView chevron() {
        ImageView view = new ImageView(context);
        int icon = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "ic_actions_chevron_right");
        if (icon != 0) view.setImageResource(icon);
        view.setImageTintList(ColorStateList.valueOf(secondary));
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(48)));
        return view;
    }

    // endregion

    // region Pictures

    /**
     * The square of a picture: the theme's highlight colour with an icon until the picture is there, a rounded square
     * (6 dp, as SoundCloud's artwork) or a circle for an artist.
     */
    ImageView artwork(boolean round, String placeholderIcon) {
        ImageView view = new ImageView(context);
        GradientDrawable background = new GradientDrawable();
        background.setColor(placeholder);
        if (round) background.setShape(GradientDrawable.OVAL);
        else background.setCornerRadius(6 * density);
        view.setBackground(background);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                if (round) outline.setOval(0, 0, v.getWidth(), v.getHeight());
                else outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 6 * density);
            }
        });
        view.setClipToOutline(true);
        int icon = Utils.getResourceIdentifier(ResourceType.DRAWABLE, placeholderIcon);
        if (icon != 0) {
            view.setImageResource(icon);
            view.setImageTintList(ColorStateList.valueOf(secondary));
        }
        view.setScaleType(ImageView.ScaleType.CENTER);
        return view;
    }

    /** Loads the picture into a view made by {@link #artwork}; it fills the square, cut to it. */
    void load(ImageView view, String address, int sideDp) {
        SearchCovers.load(view, address, dp(sideDp), (target, bitmap) -> {
            target.setImageTintList(null);
            target.setScaleType(ImageView.ScaleType.CENTER_CROP);
            target.setImageBitmap(bitmap);
        });
    }

    // endregion

    /** A filled button in the accent, as SoundCloud's main buttons. */
    TextView pill(String label, Runnable onClick) {
        TextView view = text("H4.Primary", 14, Colors.ink(accent, 0.6, 0xFF000000, 0xFFFFFFFF), label);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(22), dp(10), dp(22), dp(10));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(accent);
        shape.setCornerRadius(dp(24));
        view.setBackground(shape);
        view.setOnClickListener(v -> onClick.run());
        return view;
    }
}
