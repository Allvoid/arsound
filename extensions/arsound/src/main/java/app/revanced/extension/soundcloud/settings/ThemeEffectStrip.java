package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.theme.CustomTheme;
import app.revanced.extension.soundcloud.theme.ThemeBackdrop;
import app.revanced.extension.soundcloud.theme.ThemeEffect;

/**
 * The choice of the background animation of the custom theme as a row of small tiles that scrolls sideways: the first
 * is "no animation", the others are the animations of the shipped themes, each playing in its tile on the user's own
 * background and accent. It replaces a text list, so the animation is seen before it is chosen. Used by
 * {@link CustomThemeScreen}.
 */
final class ThemeEffectStrip extends HorizontalScrollView {
    private static final int TILE_DP = 64, CELL_DP = 76;

    /** One tile: what it stands for and the colours it can change. */
    private static final class Tile {
        final String effectId;
        final FrameLayout cell;
        final GradientDrawable swatch = new GradientDrawable();
        final GradientDrawable dot = new GradientDrawable();

        Tile(String effectId, FrameLayout cell) {
            this.effectId = effectId;
            this.cell = cell;
        }
    }

    private final SettingsUi ui;
    private final List<Tile> tiles = new ArrayList<>();

    /** @param onPicked Told the id of the effect (or {@link CustomTheme#NO_EFFECT}) the user tapped. */
    ThemeEffectStrip(SettingsUi ui, Consumer<String> onPicked) {
        super(ui.activity);
        this.ui = ui;
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout row = new LinearLayout(ui.activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(ui.dimen("spacing_m"), ui.dp(4), ui.dimen("spacing_m"), ui.dp(4));
        row.addView(createTile(CustomTheme.NO_EFFECT, text("Без анимации", "None"), null, onPicked));
        boolean animated = Settings.isThemeEffectsEnabled();
        for (ArsoundTheme.Theme theme : ArsoundTheme.all(ui.activity)) {
            if (theme.runtime != null || !ThemeEffect.exists(theme.id)) continue;
            ThemeEffect effect = animated ? ThemeBackdrop.forTheme(ui.activity, theme, .45f, .7f) : null;
            row.addView(createTile(theme.id, theme.name(), effect, onPicked));
        }
        addView(row);
    }

    private View createTile(String effectId, String name, ThemeEffect effect, Consumer<String> onPicked) {
        LinearLayout item = new LinearLayout(ui.activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setContentDescription(name);
        item.setOnClickListener(v -> onPicked.accept(effectId));

        FrameLayout cell = new FrameLayout(ui.activity);
        cell.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
        Tile tile = new Tile(effectId, cell);
        tile.swatch.setCornerRadius(ui.dp(14));
        tile.swatch.setStroke(Math.max(1, ui.dp(1)), 0x33888888);
        tile.dot.setShape(GradientDrawable.OVAL);
        LayerDrawable layers = effect == null
                ? new LayerDrawable(new Drawable[]{tile.swatch, tile.dot})
                : new LayerDrawable(new Drawable[]{tile.swatch, effect, tile.dot});
        int dotIndex = layers.getNumberOfLayers() - 1;
        // Without an animation the accent sits in the middle; with one it moves to the corner.
        if (effect == null) layers.setLayerInset(dotIndex, ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(22));
        else layers.setLayerInset(dotIndex, ui.dp(40), ui.dp(40), ui.dp(8), ui.dp(8));
        View face = new View(ui.activity);
        face.setBackground(layers);
        face.setClipToOutline(true);
        cell.addView(face, new FrameLayout.LayoutParams(ui.dp(TILE_DP), ui.dp(TILE_DP)));
        item.addView(cell, new LinearLayout.LayoutParams(ui.dp(TILE_DP + 6), ui.dp(TILE_DP + 6)));
        tiles.add(tile);

        TextView label = ui.createText("Body.Secondary", name);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        item.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.dp(CELL_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        item.setLayoutParams(params);
        return item;
    }

    /** Shows the user's colours in the tiles and rings the chosen one. */
    void show(CustomTheme theme) {
        int accent = ArsoundTheme.palette(ui.activity, "special");
        int surface = Color.parseColor(theme.darkBg);
        int dot = Color.parseColor(theme.accent);
        for (Tile tile : tiles) {
            tile.swatch.setColor(surface);
            tile.dot.setColor(dot);
            tile.swatch.invalidateSelf();
            boolean chosen = tile.effectId.equals(theme.effect) || !ThemeEffect.exists(theme.effect) && CustomTheme.NO_EFFECT.equals(tile.effectId);
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(ui.dp(17));
            if (chosen) ring.setStroke(ui.dp(2), accent);
            tile.cell.setBackground(ring);
        }
    }
}
