package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.theme.CustomTheme;
import app.revanced.extension.soundcloud.theme.RuntimeThemes;
import app.revanced.extension.soundcloud.theme.ThemeBackdrop;
import app.revanced.extension.soundcloud.theme.ThemeEffect;

/**
 * The theme picker as a drop-down.
 */
final class ThemeDropdown {
    private final ReVancedSettingsActivity activity;
    private final SettingsUi ui;

    ThemeDropdown(SettingsUi ui) {
        this.ui = ui;
        this.activity = ui.activity;
    }

    /** One entry of the theme list: the original look has no theme object. */
    private static final class ThemeChoice {
        final String id, name, description;
        final ArsoundTheme.Theme theme;

        ThemeChoice(String id, String name, String description, ArsoundTheme.Theme theme) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.theme = theme;
        }
    }

    private List<ThemeChoice> themeChoices() {
        List<ThemeChoice> choices = new ArrayList<>();
        choices.add(new ThemeChoice(ArsoundTheme.SOUNDCLOUD, "SoundCloud",
                text("Стандартный вид SoundCloud: чёрно-белый с оранжевым.", "SoundCloud's own look: black and white with orange."),
                null));
        for (ArsoundTheme.Theme theme : ArsoundTheme.all(activity)) {
            choices.add(new ThemeChoice(theme.id, theme.name(), theme.description(), theme));
        }
        return choices;
    }

    /**
     * The theme picker as a drop-down: a card with the chosen theme, which unfolds into the list of themes.
     * Every theme shows its colours and its animation in a small tile.
     */
    View create() {
        List<ThemeChoice> choices = themeChoices();
        String current = ArsoundTheme.current();
        ThemeChoice chosen = choices.get(0);
        for (ThemeChoice choice : choices) if (choice.id.equals(current)) chosen = choice;

        int accent = ArsoundTheme.palette(activity, "special");
        LinearLayout card = ui.createColumn();
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setColor(ArsoundTheme.palette(activity, "highlight"));
        cardShape.setCornerRadius(ui.dp(18));
        cardShape.setStroke(ui.dp(1), (accent & 0x00FFFFFF) | 0x40000000);
        card.setBackground(cardShape);
        card.setClipToOutline(true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.leftMargin = cardParams.rightMargin = ui.dimen("spacing_m");
        cardParams.bottomMargin = ui.dimen("spacing_s");
        card.setLayoutParams(cardParams);

        View header = createThemeOption(chosen, false, accent);
        ImageView chevron = new ImageView(activity);
        chevron.setImageDrawable(ui.lineIcon(new float[]{.22f, .4f, .5f, .64f, .78f, .4f}, ui.themeColor("themeColorPrimary")));
        ((LinearLayout) header).addView(chevron, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
        header.setContentDescription(text("Тема: ", "Theme: ") + chosen.name);
        card.addView(header);

        LinearLayout options = ui.createColumn();
        options.setVisibility(View.GONE);
        View divider = new View(activity);
        divider.setBackgroundColor((ui.themeColor("themeColorPrimary") & 0x00FFFFFF) | 0x1A000000);
        options.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1) / 2)));
        for (ThemeChoice choice : choices) {
            View option = createThemeOption(choice, choice.id.equals(current), accent);
            option.setOnClickListener(v -> {
                ui.foldOptions(options, chevron, false);
                if (choice.id.equals(ArsoundTheme.current())) return;
                // The custom theme has no colours of the user's yet: the editor opens first.
                if (RuntimeThemes.CUSTOM.equals(choice.id) && !CustomTheme.isSaved()) {
                    activity.startActivity(ui.screenIntent(CustomThemeScreen.ID));
                    return;
                }
                ArsoundTheme.setCurrent(activity, choice.id);
                ArsoundTheme.restartApp(activity);
            });
            options.addView(option);
        }
        card.addView(options);
        header.setOnClickListener(v -> ui.foldOptions(options, chevron, options.getVisibility() != View.VISIBLE));
        return card;
    }

    /** A theme row: a tile with its colours and animation, the name and the description, a tick if chosen. */
    private View createThemeOption(ThemeChoice choice, boolean selected, int accent) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(ui.themeAttribute(android.R.attr.selectableItemBackground));
        row.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        row.setContentDescription(choice.name);

        // Tiles show the dark look: SoundCloud's own screens, where the animation plays, stay dark. A theme that is
        // light whatever the phone uses is shown as it is.
        boolean night = choice.theme == null || !choice.theme.lightOnly;
        int surface = choice.theme == null ? (night ? 0xFF121212 : 0xFFFFFFFF) : choice.theme.surface(night);
        int dotColor = choice.theme == null ? 0xFFFF5500 : choice.theme.accent(night);

        GradientDrawable swatch = new GradientDrawable();
        swatch.setColor(surface);
        swatch.setCornerRadius(ui.dp(14));
        swatch.setStroke(ui.dp(1), 0x33888888);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(dotColor);
        ThemeEffect effect = choice.theme == null || !Settings.isThemeEffectsEnabled()
                ? null : ThemeBackdrop.forTheme(activity, choice.theme, .45f, .7f);
        LayerDrawable layers = effect == null
                ? new LayerDrawable(new Drawable[]{swatch, dot})
                : new LayerDrawable(new Drawable[]{swatch, effect, dot});
        int dotIndex = layers.getNumberOfLayers() - 1;
        // Without an animation the accent sits in the middle; with one it moves to the corner.
        if (effect == null) layers.setLayerInset(dotIndex, ui.dp(17), ui.dp(17), ui.dp(17), ui.dp(17));
        else layers.setLayerInset(dotIndex, ui.dp(36), ui.dp(36), ui.dp(6), ui.dp(6));
        View tile = new View(activity);
        tile.setBackground(layers);
        tile.setClipToOutline(true);
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52));
        tileParams.rightMargin = ui.dp(14);
        row.addView(tile, tileParams);

        LinearLayout texts = ui.createColumn();
        TextView name = ui.createText("H4.Primary", choice.name);
        if (selected) name.setTextColor(accent);
        texts.addView(name);
        texts.addView(ui.createText("Body.Secondary", choice.description));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (selected) {
            ImageView tick = new ImageView(activity);
            tick.setImageDrawable(ui.lineIcon(new float[]{.2f, .52f, .42f, .72f, .82f, .3f}, accent));
            row.addView(tick, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
        }
        return row;
    }
}
