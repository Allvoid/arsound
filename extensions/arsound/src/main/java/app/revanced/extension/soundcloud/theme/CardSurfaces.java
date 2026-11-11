package app.revanced.extension.soundcloud.theme;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.compose.ui.Modifier;
import androidx.compose.ui.draw.DrawModifierKt;
import androidx.compose.ui.graphics.AndroidCanvas_androidKt;
import androidx.compose.ui.graphics.drawscope.ContentDrawScope;
import androidx.compose.ui.graphics.drawscope.DrawContext;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Colors;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * Puts SoundCloud's rows on cards ({@link CardLook}) when "Cards and outlines" is on: the rows of the Library and the
 * cells of lists that are views (playlists, tracks), and the cells that Compose draws (the tracks of the likes and of
 * a playlist, the rows of SoundCloud's own settings and menus). Off, nothing is touched.
 * <p>
 * A row keeps its size and its place: the card is drawn inside it, a little in from its edges, so a list scrolls and
 * lays out as before.
 */
@SuppressWarnings({"unused", "rawtypes"})
public final class CardSurfaces {
    /** How far the card is in from the edges of its row, in dp: rows follow each other with a small gap. */
    private static final float INSET_SIDE_DP = 8f, INSET_ENDS_DP = 3f;
    /** The same for a tile of a carousel, which has its own padding inside its box. */
    private static final float TILE_INSET_SIDE_DP = 3f, TILE_INSET_ENDS_DP = 2f;
    /** The look the main screen was last built with, to build it again when the choices changed in the settings. */
    private static String mainScreenKey;
    /** The screen shown now: the cells of Compose that cannot read their view take their colours from it. */
    private static WeakReference<Activity> top = new WeakReference<>(null);

    private CardSurfaces() {
    }

    /** The settings changed: nothing is kept that was made with the old look. */
    public static void onLookChanged() {
        DRAWS.clear();
    }

    /**
     * Called when a screen comes back. The main screen is built again when the cards were switched or changed while
     * the settings were open: its rows were made with the old choice.
     */
    public static void onActivityResumed(Activity activity) {
        try {
            top = new WeakReference<>(activity);
            if (!activity.getClass().getName().endsWith(".MainActivity") || activity.isFinishing()) return;
            String key = CardLook.of(activity).key();
            String shown = mainScreenKey;
            mainScreenKey = key;
            // Only when cards are or were on: a change of the theme alone is not this class's to follow.
            if (shown != null && !shown.equals(key) && (CardLook.isOn() || shown.startsWith("true"))) activity.recreate();
        } catch (Throwable ex) {
            Logger.printException(() -> "Cards: could not follow the settings", ex);
        }
    }

    // region Views: the rows of the Library

    /**
     * Gives a row of the Library its card. Called when the row is bound ({@code ArsoundTheme.libraryRowIcon}); the
     * row keeps its ripple, cut to the card.
     */
    public static void styleRow(View row) {
        if (row == null || !CardLook.isOn()) return;
        try {
            card(row);
            // The rows of the Library lie side by side in one layout, and not every one of them comes through this
            // hook as a view (the Downloads row is built again by name): the others are found beside this one.
            if (!(row.getParent() instanceof ViewGroup)) return;
            ViewGroup rows = (ViewGroup) row.getParent();
            for (int i = 0; i < rows.getChildCount(); i++) {
                View other = rows.getChildAt(i);
                if (other != row && isLibraryRow(other)) card(other);
            }
        } catch (Throwable ex) {
            // A hook where a row is bound: the row without a card is always a good answer.
            Logger.printException(() -> "Cards: could not style a row", ex);
        }
    }

    /**
     * Gives a cell of a list its card. Injection point, at the end of the {@code n(ViewState)} that binds a view cell
     * (a playlist, a track): the card is set after the cell has set its own background. A cell is bound again and
     * again, and a card that was made with the same look is left as it is.
     */
    public static void styleCell(View cell) {
        if (cell == null || !CardLook.isOn()) return;
        try {
            card(cell);
        } catch (Throwable ex) {
            // A hook where a cell is bound: the cell without a card is always a good answer.
            Logger.printException(() -> "Cards: could not style a cell", ex);
        }
    }

    /** The rows of the Library are named library_header_<row>. */
    private static boolean isLibraryRow(View view) {
        if (view.getId() == View.NO_ID) return false;
        try {
            return view.getResources().getResourceEntryName(view.getId()).startsWith("library_header_");
        } catch (Exception ex) {
            return false;
        }
    }

    /** The card a row has, and the look it was made with: a row is bound again and again. */
    private static final class Carded {
        final String key;
        final android.graphics.drawable.Drawable background;

        Carded(String key, android.graphics.drawable.Drawable background) {
            this.key = key;
            this.background = background;
        }
    }

    private static final java.util.Map<View, Carded> CARDED = new java.util.WeakHashMap<>();

    private static void card(View row) {
        Context context = row.getContext();
        CardLook card = CardLook.of(context);
        String key = card.key();
        Carded had = CARDED.get(row);
        // A cell that sets its own background when it is bound has put it back over the card: the card is made again.
        if (had != null && had.key.equals(key) && row.getBackground() == had.background) return;
        float density = context.getResources().getDisplayMetrics().density;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(card.fill);
        shape.setCornerRadius(card.radiusDp * density);
        if (card.outline != 0) shape.setStroke(Math.max(1, Math.round(density)), card.outline);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(card.radiusDp * density);
        int ripple = Colors.withAlpha(ArsoundTheme.palette(context, "primary"), 0x22);
        RippleDrawable background = new RippleDrawable(ColorStateList.valueOf(ripple), shape, mask);
        row.setBackground(background);
        CARDED.put(row, new Carded(key, background));

        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            int side = Math.round(INSET_SIDE_DP * density), ends = Math.round(INSET_ENDS_DP * density);
            if (margins.leftMargin != side || margins.rightMargin != side
                    || margins.topMargin != ends || margins.bottomMargin != ends) {
                margins.setMargins(side, ends, side, ends);
                row.setLayoutParams(margins);
            }
        }
    }

    // endregion

    // region Compose: the tracks of lists

    /** {@code DrawContext.getSize-NH-jbRc()}: the size of what is drawn, two floats packed into a long. */
    private static Method sizeOf;
    private static boolean sizeLookedUp;
    private static boolean drawFailed;

    /**
     * What draws the card of a cell, one object for each look: Compose then sees the same modifier at every pass and
     * draws a cell again only when it must. There are as many as there are looks on screen (the dark and the light
     * one of a theme, at most). Main thread, as all of Compose's composing and drawing.
     */
    private static final java.util.Map<String, Function1> DRAWS = new java.util.HashMap<>();

    /**
     * Injection point, in the composables that have no view at hand (the rows of SoundCloud's settings and menus): the
     * modifier of the row's box as the row has made it, and the card on it in the colours of the screen shown now.
     */
    public static Modifier row(Modifier modifier) {
        Activity screen = top.get();
        if (!CardLook.isOn() || modifier == null || screen == null) return modifier;
        return withCard(modifier, screen, false);
    }

    /**
     * Injection point, in the tiles of carousels (a playlist, a track, a user): the modifier of the tile's fixed-width
     * box. The tile has its own padding inside that box, so the card lies close to the box's edges.
     */
    public static Modifier tile(Modifier modifier) {
        Activity screen = top.get();
        if (!CardLook.isOn() || modifier == null || screen == null) return modifier;
        return withCard(modifier, screen, true);
    }

    /**
     * Injection point, in SoundCloud's swipeable list cell ({@code SwipeCellKt.a}): the modifier of the cell's box,
     * right where the cell makes it, and the view the list is shown in. The card is drawn under the cell's own
     * content, in the colours of the screen the view belongs to: over a wallpaper a main screen keeps its dark look
     * while the rest of the app is light, and a card in the app's colours hid the light text on it.
     * <p>
     * The modifier the cell was called with is left alone: the cell keeps it for the next time it is composed, and a
     * card added there was added once more at every pass.
     */
    public static Modifier cell(Modifier modifier, View host) {
        if (!CardLook.isOn() || modifier == null || host == null) return modifier;
        return withCard(modifier, host.getContext(), false);
    }

    private static Modifier withCard(Modifier modifier, Context context, boolean tile) {
        try {
            CardLook card = CardLook.of(context);
            String key = (tile ? "tile/" : "row/") + card.key();
            Function1 draw = DRAWS.get(key);
            if (draw == null) {
                // The looks of settings left behind are few and small, but they need not pile up.
                if (DRAWS.size() > 8) DRAWS.clear();
                draw = new CardDraw(card, context.getResources().getDisplayMetrics().density, tile);
                DRAWS.put(key, draw);
            }
            return DrawModifierKt.drawWithContent(modifier, draw);
        } catch (Throwable ex) {
            Logger.printException(() -> "Cards: could not wrap a cell", ex);
            return modifier;
        }
    }

    /** Draws one look of the card under the content of a cell. */
    private static final class CardDraw implements Function1 {
        private final CardLook card;
        private final float density;
        /** A tile carries its own padding; a row lies further in from the edges of its box. */
        private final boolean tile;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        CardDraw(CardLook card, float density, boolean tile) {
            this.card = card;
            this.density = density;
            this.tile = tile;
            fill.setColor(card.fill);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1f, density));
            line.setColor(card.outline);
        }

        @Override
        public Object invoke(Object scope) {
            ContentDrawScope content = (ContentDrawScope) scope;
            try {
                draw(content);
            } catch (Throwable ex) {
                if (!drawFailed) {
                    drawFailed = true;
                    Logger.printException(() -> "Cards: could not draw a card", ex);
                }
            }
            content.drawContent();
            return Unit.INSTANCE;
        }

        /** Draws the card on the cell's own canvas. */
        private void draw(ContentDrawScope scope) throws Exception {
            DrawContext drawing = scope.getDrawContext();
            if (!sizeLookedUp) {
                sizeLookedUp = true;
                sizeOf = DrawContext.class.getMethod("getSize-NH-jbRc");
            }
            if (sizeOf == null) return;
            long size = (Long) sizeOf.invoke(drawing);
            float width = Float.intBitsToFloat((int) (size >> 32)), height = Float.intBitsToFloat((int) size);
            if (!(width > 0f) || !(height > 0f)) return;

            // Tiles lie side by side with no gap and carry their own padding: the card is in from the box by a little, which
            // parts two cards and keeps the outline off the edge a list clips at.
            float side = (tile ? TILE_INSET_SIDE_DP : INSET_SIDE_DP) * density, ends = (tile ? TILE_INSET_ENDS_DP : INSET_ENDS_DP) * density;
            // A cell too small for a card (a divider, a spacer) is left as it is.
            if (width <= 32 * density || height <= 18 * density) return;
            float radius = Math.min(card.radiusDp * density, (height - 2 * ends) / 2f);
            Canvas canvas = AndroidCanvas_androidKt.getNativeCanvas(drawing.getCanvas());
            bounds.set(side, ends, width - side, height - ends);
            if ((card.fill >>> 24) != 0) canvas.drawRoundRect(bounds, radius, radius, fill);
            if (card.outline != 0) {
                float half = line.getStrokeWidth() / 2f;
                bounds.inset(half, half);
                canvas.drawRoundRect(bounds, Math.max(0f, radius - half), Math.max(0f, radius - half), line);
            }
        }
    }

    // endregion
}
