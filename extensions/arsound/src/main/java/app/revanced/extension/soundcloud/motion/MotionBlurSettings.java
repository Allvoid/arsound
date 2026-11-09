package app.revanced.extension.soundcloud.motion;

import app.revanced.extension.soundcloud.settings.BoolSettings;
import app.revanced.extension.soundcloud.settings.Settings;

/** What the user chose for the motion blur, read at the start of each gesture. */
public final class MotionBlurSettings {
    final boolean scroll;
    final boolean swipes;
    final int amount;
    final int stretch;

    private MotionBlurSettings(boolean scroll, boolean swipes, int amount, int stretch) {
        this.scroll = scroll;
        this.swipes = swipes;
        this.amount = amount;
        this.stretch = stretch;
    }

    /** Whether the blur is switched on at all. */
    public static boolean enabled() {
        return BoolSettings.MOTION_BLUR.get();
    }

    public static int amount() {
        return clamp((int) Settings.getLong(Settings.MOTION_BLUR_AMOUNT, MotionBlurMath.AMOUNT_DEFAULT), 0, 100);
    }

    public static int stretch() {
        return clamp((int) Settings.getLong(Settings.MOTION_BLUR_STRETCH, MotionBlurMath.STRETCH_DEFAULT),
                MotionBlurMath.STRETCH_MIN, MotionBlurMath.STRETCH_MAX);
    }

    public static void setAmount(int amount) {
        Settings.putLong(Settings.MOTION_BLUR_AMOUNT, clamp(amount, 0, 100));
    }

    public static void setStretch(int hundredths) {
        Settings.putLong(Settings.MOTION_BLUR_STRETCH, clamp(hundredths, MotionBlurMath.STRETCH_MIN, MotionBlurMath.STRETCH_MAX));
    }

    /** The whole choice, or null when the blur is off. */
    static MotionBlurSettings read() {
        if (!enabled()) return null;
        return new MotionBlurSettings(BoolSettings.MOTION_BLUR_SCROLL.get(), BoolSettings.MOTION_BLUR_SWIPES.get(),
                amount(), stretch());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
