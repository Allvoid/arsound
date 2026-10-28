package app.revanced.extension.soundcloud.settings.search;

/** How many typing mistakes lie between two words. A swapped pair of neighbouring letters counts as one mistake. */
public final class EditDistance {
    private EditDistance() {
    }

    /** True if {@code a} turns into {@code b} with at most {@code max} inserts, deletes, replaces or swaps. */
    public static boolean within(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) return false;
        int[] beforePrevious = new int[b.length() + 1];
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        int previousBest = 0;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowBest = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int value = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    value = Math.min(value, beforePrevious[j - 2] + 1);
                }
                current[j] = value;
                if (value < rowBest) rowBest = value;
            }
            // Every cell of this row and of the one before is already too far (a swap reaches two rows back),
            // and later rows cannot get closer.
            if (rowBest > max && previousBest >= max) return false;
            previousBest = rowBest;
            int[] free = beforePrevious;
            beforePrevious = previous;
            previous = current;
            current = free;
        }
        return previous[b.length()] <= max;
    }
}
