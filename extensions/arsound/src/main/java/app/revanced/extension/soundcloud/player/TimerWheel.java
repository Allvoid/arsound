package app.revanced.extension.soundcloud.player;

import java.util.Locale;

/**
 * A wheel of the timer window: the numbers from {@code min} to {@code max} by {@code step}. The wheel itself counts
 * from 0 (an index); the label of each index is the number it stands for.
 */
final class TimerWheel {
    private final int min;
    private final int max;
    private final int step;
    private final boolean twoDigits;

    TimerWheel(int min, int max, int step, boolean twoDigits) {
        this.min = min;
        this.max = max;
        this.step = step;
        this.twoDigits = twoDigits;
    }

    /** How many positions the wheel has. */
    int count() {
        return (max - min) / step + 1;
    }

    /** The label of each position; a wheel that steps by more than one is always two digits. */
    String[] labels() {
        String[] labels = new String[count()];
        for (int i = 0; i < labels.length; i++) {
            int value = min + i * step;
            labels[i] = twoDigits || step > 1 ? String.format(Locale.ROOT, "%02d", value) : String.valueOf(value);
        }
        return labels;
    }
}
