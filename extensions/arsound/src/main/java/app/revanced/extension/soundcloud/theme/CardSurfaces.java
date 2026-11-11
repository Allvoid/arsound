package app.revanced.extension.soundcloud.theme;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import androidx.compose.ui.Modifier;
import androidx.compose.ui.draw.DrawModifierKt;
import androidx.compose.ui.graphics.AndroidCanvas_androidKt;
import androidx.compose.ui.graphics.drawscope.ContentDrawScope;
import androidx.compose.ui.graphics.drawscope.DrawContext;

import java.lang.reflect.Method;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
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
    /** The look is asked of the settings and the theme at most this often while cards are drawn. */
    private static final long LOOK_FRESH_MS = 1_000;

    private static volatile CardLook look;
    private static volatile long lookAt;
    /** The look the main screen was last built with, to build it again when the choices changed in the settings. */
    private static String mainScreenKey;

    private CardSurfaces() {
    }

    private static CardLook look(Context context) {
        long now = SystemClock.uptimeMillis();
        CardLook current = look;
        if (current == null || now - lookAt > LOOK_FRESH_MS) {
            current = CardLook.of(context);
            look = current;
            lookAt = now;
        }
        return current;
    }

    /** The settings changed: the next card is drawn with the new look. */
    public static void onLookChanged() {
        look = null;
    }

    /**
     * Called when a screen comes back. The main screen is built again when the cards were switched or changed while
     * the settings were open: its rows were made with the old choice.
     */
    public static void onActivityResumed(Activity activity) {
        try {
            if (!activity.getClass().getName().endsWith(".MainActivity") || activity.isFinishing()) return;
            look = null;
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
        CardLook card = look(context);
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

    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint LINE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF BOUNDS = new RectF();
    /** {@code DrawContext.getSize-NH-jbRc()}: the size of what is drawn, two floats packed into a long. */
    private static Method sizeOf;
    private static boolean sizeLookedUp;
    private static boolean drawFailed;

    static {
        LINE.setStyle(Paint.Style.STROKE);
    }

    /** One object for every cell: Compose sees the same modifier at every pass and draws the cell again only when it must. */
    private static final Function1 CARD = scope -> {
        ContentDrawScope content = (ContentDrawScope) scope;
        try {
            drawCard(content);
        } catch (Throwable ex) {
            if (!drawFailed) {
                drawFailed = true;
                Logger.printException(() -> "Cards: could not draw a card", ex);
            }
        }
        content.drawContent();
        return Unit.INSTANCE;
    };

    /**
     * Injection point: the start of SoundCloud's swipeable list cell ({@code SwipeCellKt.a}), with its modifier. The
     * card is drawn under the cell's own content.
     */
    public static Modifier cell(Modifier modifier) {
        if (!CardLook.isOn()) return modifier;
        try {
            return DrawModifierKt.drawWithContent(modifier == null ? Modifier.Companion : modifier, CARD);
        } catch (Throwable ex) {
            Logger.printException(() -> "Cards: could not wrap a cell", ex);
            return modifier;
        }
    }

    /** Draws the card on the cell's own canvas. Main thread (Compose draws there). */
    private static void drawCard(ContentDrawScope scope) throws Exception {
        Context context = Utils.peekContext();
        if (context == null) return;
        DrawContext drawing = scope.getDrawContext();
        if (!sizeLookedUp) {
            sizeLookedUp = true;
            sizeOf = DrawContext.class.getMethod("getSize-NH-jbRc");
        }
        if (sizeOf == null) return;
        long size = (Long) sizeOf.invoke(drawing);
        float width = Float.intBitsToFloat((int) (size >> 32)), height = Float.intBitsToFloat((int) size);
        if (!(width > 0f) || !(height > 0f)) return;

        CardLook card = look(context);
        float density = context.getResources().getDisplayMetrics().density;
        float side = INSET_SIDE_DP * density, ends = INSET_ENDS_DP * density;
        // A cell too small for a card (a divider, a spacer) is left as it is.
        if (width <= side * 4 || height <= ends * 6) return;
        float radius = Math.min(card.radiusDp * density, (height - 2 * ends) / 2f);
        Canvas canvas = AndroidCanvas_androidKt.getNativeCanvas(drawing.getCanvas());
        BOUNDS.set(side, ends, width - side, height - ends);
        if ((card.fill >>> 24) != 0) {
            FILL.setColor(card.fill);
            canvas.drawRoundRect(BOUNDS, radius, radius, FILL);
        }
        if (card.outline != 0) {
            float line = Math.max(1f, density);
            LINE.setStrokeWidth(line);
            LINE.setColor(card.outline);
            BOUNDS.inset(line / 2f, line / 2f);
            canvas.drawRoundRect(BOUNDS, Math.max(0f, radius - line / 2f), Math.max(0f, radius - line / 2f), LINE);
        }
    }

    // endregion
}
