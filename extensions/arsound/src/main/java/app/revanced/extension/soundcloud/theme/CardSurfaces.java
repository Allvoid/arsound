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

import java.lang.reflect.Method;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Colors;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * Puts SoundCloud's rows on cards ({@link CardLook}) when "Cards and outlines" is on: the rows of the Library, which
 * are views, and the tracks of lists (the likes, a playlist, the search), which Compose draws. Off, nothing is touched.
 * <p>
 * A row keeps its size and its place: the card is drawn inside it, a little in from its edges, so a list scrolls and
 * lays out as before.
 */
@SuppressWarnings({"unused", "rawtypes"})
public final class CardSurfaces {
    /** How far the card is in from the edges of its row, in dp: rows follow each other with a small gap. */
    private static final float INSET_SIDE_DP = 8f, INSET_ENDS_DP = 3f;
    /** The look the main screen was last built with, to build it again when the choices changed in the settings. */
    private static String mainScreenKey;

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

    /** The rows of the Library are named library_header_<row>. */
    private static boolean isLibraryRow(View view) {
        if (view.getId() == View.NO_ID) return false;
        try {
            return view.getResources().getResourceEntryName(view.getId()).startsWith("library_header_");
        } catch (Exception ex) {
            return false;
        }
    }

    /** The rows that have their card, and the look it was made with: a row is bound again and again. */
    private static final java.util.Map<View, String> CARDED = new java.util.WeakHashMap<>();

    private static void card(View row) {
        Context context = row.getContext();
        CardLook card = CardLook.of(context);
        String key = card.key();
        if (key.equals(CARDED.get(row))) return;
        CARDED.put(row, key);
        float density = context.getResources().getDisplayMetrics().density;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(card.fill);
        shape.setCornerRadius(card.radiusDp * density);
        if (card.outline != 0) shape.setStroke(Math.max(1, Math.round(density)), card.outline);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(card.radiusDp * density);
        int ripple = Colors.withAlpha(ArsoundTheme.palette(context, "primary"), 0x22);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), shape, mask));

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
        try {
            Context context = host.getContext();
            CardLook card = CardLook.of(context);
            String key = card.key();
            Function1 draw = DRAWS.get(key);
            if (draw == null) {
                // The looks of settings left behind are few and small, but they need not pile up.
                if (DRAWS.size() > 8) DRAWS.clear();
                draw = new CardDraw(card, context.getResources().getDisplayMetrics().density);
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
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        CardDraw(CardLook card, float density) {
            this.card = card;
            this.density = density;
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

            float side = INSET_SIDE_DP * density, ends = INSET_ENDS_DP * density;
            // A cell too small for a card (a divider, a spacer) is left as it is.
            if (width <= side * 4 || height <= ends * 6) return;
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
