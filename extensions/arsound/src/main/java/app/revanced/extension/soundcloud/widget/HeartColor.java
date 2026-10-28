package app.revanced.extension.soundcloud.widget;

import android.graphics.Color;

/** Fits a colour to the card of the widget, so the heart stands out from it in the phone's day and night look. */
final class HeartColor {
    /**
     * The luminance (as WCAG defines it) the heart is kept within. The card behind it is white or black at 65% over
     * the wallpaper; the worst cases are a light grey card (0xF0F0F0) and a dark one (0x1C1C1C). A luminance of 0.14
     * or more keeps the contrast at 3 or better on the dark card, 0.25 or less on the light one. The day band is that,
     * so it stands out from either card: the launcher can change the look without asking for a new update, and the
     * colour of the day look still reads on the dark card. The night band lets a bright cover stay bright, as the dark
     * card has the contrast to spare. When the phone flips between its looks the launcher redraws the card by itself
     * but keeps the heart it was given, so {@link WidgetLook#watch} has the widget built again at once; only if the app is
     * not running at that moment is the heart a light colour on a light card until the next update.
     */
    private static final double MIN_LUMINANCE = 0.14;
    private static final double MAX_LUMINANCE_DAY = 0.25;
    private static final double MAX_LUMINANCE_NIGHT = 0.45;

    private HeartColor() {
    }

    /**
     * The colour with this hue, brightened or darkened until its luminance is within the band of the look, and made
     * less saturated if even the brightest is too dark (a deep blue is lighter only by mixing with white).
     */
    static int fit(float hue, float saturation, float value, boolean night) {
        double maxLuminance = night ? MAX_LUMINANCE_NIGHT : MAX_LUMINANCE_DAY;
        float[] hsv = {hue, saturation, Math.max(0.35f, Math.min(1f, value))};
        // Each step is a few percent of the luminance, far less than the band, so the loop settles; the limit only
        // keeps a colour that cannot settle from looping.
        for (int i = 0; i < 40; i++) {
            double luminance = luminance(Color.HSVToColor(hsv));
            if (luminance > maxLuminance && hsv[2] > 0.3f) {
                hsv[2] -= 0.03f;
            } else if (luminance < MIN_LUMINANCE && hsv[2] < 1f) {
                hsv[2] = Math.min(1f, hsv[2] + 0.03f);
            } else if (luminance < MIN_LUMINANCE && hsv[1] > 0.3f) {
                hsv[1] -= 0.03f;
            } else {
                break;
            }
        }
        return Color.HSVToColor(hsv);
    }

    /** The relative luminance of a colour, 0 for black to 1 for white. */
    private static double luminance(int color) {
        return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color)) + 0.0722 * linear(Color.blue(color));
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
