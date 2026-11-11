package app.revanced.extension.soundcloud.theme;

import android.content.Context;

import app.revanced.extension.soundcloud.settings.BoolSettings;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Colors;

/**
 * How a card looks: the rounded, softly outlined panel Arsound's settings group their rows in. With "Cards and
 * outlines" on, the rows of the Library and the tracks of lists lie on such cards too ({@link CardSurfaces}), and the
 * user chooses the roundness of the corners, the outline and how much of what is behind shows through; the cards of the
 * settings follow the same choices. Off, the settings keep their look and SoundCloud's rows stay plain.
 * <p>
 * The colours come from the theme: the fill is its "highlight" colour, the outline its accent or its text colour, so a
 * card fits the dark and the light look of every theme.
 * <p>
 * The idea of cards with a choice of outline for the Library, the settings and the track lists is KayLight2's
 * (github.com/KayLight2/arsound, branch feature/cards-and-borders).
 */
public final class CardLook {
    /** The outline of a card: the accent of the theme, a plain line in the colour of the text, or none. */
    public static final int OUTLINE_ACCENT = 0, OUTLINE_PLAIN = 1, OUTLINE_NONE = 2;

    /** The corner radius of the cards of the settings, in dp: the default here, so switching the cards on changes nothing there. */
    public static final int DEFAULT_RADIUS_DP = 18;
    public static final long[] RADIUS_CHOICES_DP = {6, 12, DEFAULT_RADIUS_DP, 24};
    /** How solid the fill is, in percent: the default is that of the settings' cards over a wallpaper. */
    public static final int DEFAULT_OPACITY = 82;

    /** The fill and the outline as colours with their alpha; an outline of 0 is none. */
    public final int fill, outline;
    public final int radiusDp;

    private CardLook(int fill, int outline, int radiusDp) {
        this.fill = fill;
        this.outline = outline;
        this.radiusDp = radiusDp;
    }

    /** Whether the user switched the cards on. */
    public static boolean isOn() {
        return BoolSettings.CARDS_ENABLED.get();
    }

    /** The look chosen in the settings, in the colours of the theme of this context. */
    public static CardLook of(Context context) {
        int radius = (int) Math.max(0, Math.min(40, Settings.getLong(Settings.CARDS_RADIUS, DEFAULT_RADIUS_DP)));
        int opacity = (int) Settings.getLong(Settings.CARDS_OPACITY, DEFAULT_OPACITY);
        int outline = (int) Settings.getLong(Settings.CARDS_OUTLINE, OUTLINE_ACCENT);
        return new CardLook(
                fill(ArsoundTheme.palette(context, "highlight"), opacity),
                outline(outline, ArsoundTheme.palette(context, "special"), ArsoundTheme.palette(context, "primary")),
                radius);
    }

    /** The fill: the colour at this many percent, from clear (0) to solid (100). */
    static int fill(int colour, int opacityPercent) {
        int percent = Math.max(0, Math.min(100, opacityPercent));
        return Colors.withAlpha(colour, Math.round(255 * percent / 100f));
    }

    /**
     * The outline: the accent at the strength of the settings' cards, the text colour as a faint plain line (light on
     * a dark look, dark on a light one), or none.
     */
    static int outline(int choice, int accent, int text) {
        if (choice == OUTLINE_NONE) return 0;
        return choice == OUTLINE_PLAIN ? Colors.withAlpha(text, 0x38) : Colors.withAlpha(accent, 0x66);
    }

    /** What tells one look from another: the choices and the colours they gave. For screens to notice a change. */
    public String key() {
        return isOn() + "/" + Integer.toHexString(fill) + "/" + Integer.toHexString(outline) + "/" + radiusDp;
    }
}
