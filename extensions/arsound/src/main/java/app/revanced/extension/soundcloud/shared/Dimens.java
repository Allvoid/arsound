package app.revanced.extension.soundcloud.shared;

import android.content.Context;
import android.util.TypedValue;

/**
 * Density-independent pixels (dp) to screen pixels. The three methods differ only in how the result is rounded; a
 * screen picks the one its drawing was made for, so a value never moves by a pixel when the helper is shared.
 */
public final class Dimens {
    private Dimens() {
    }

    /** The size in pixels, as an exact fraction: for drawing with floats. */
    public static float dpExact(Context context, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    /** The size in whole pixels, rounded to the nearest: for view sizes, paddings and margins. */
    public static int dp(Context context, float dp) {
        return Math.round(dpExact(context, dp));
    }

    /** The size in whole pixels, the fraction cut off (not rounded). */
    public static int dpTruncated(Context context, float dp) {
        return (int) dpExact(context, dp);
    }
}
