package app.revanced.extension.soundcloud.wallpaper;

/**
 * Small decisions of the Wallhaven browser that are plain arithmetic: how many columns the grid has, how tall a cell is
 * and how many filters differ from the usual. Pure rules with no Android, used by {@code WallhavenScreen}.
 */
public final class WallhavenRules {
    /** A column is at least this wide, in dp, so a thumbnail is not too small to judge. */
    private static final int COLUMN_DP = 120;

    private WallhavenRules() {
    }

    /**
     * The number of columns of the grid. Tall pictures get as many columns of at least 120 dp as fit (3 on a phone of
     * 360 dp and more, 2 on a very narrow one); wide pictures need room and always get 2.
     */
    public static int columns(int widthDp, boolean portrait) {
        if (!portrait) return 2;
        return Math.max(2, widthDp / COLUMN_DP);
    }

    /** The height of a cell as a part of its width: the shape of the pictures asked for. */
    public static float cellRatio(boolean portrait) {
        // Phones are about 16:9 or taller; the middle of a taller picture is what the screen shows.
        return portrait ? 16f / 9f : .6f;
    }

    /**
     * How many filters are away from the usual, for the number on the filter button: not every category, wide pictures
     * instead of tall ones, the 18+ mode on.
     */
    public static int activeFilters(int categories, boolean portrait, boolean adult) {
        int count = 0;
        if ((categories & (Wallhaven.GENERAL | Wallhaven.ANIME | Wallhaven.PEOPLE))
                != (Wallhaven.GENERAL | Wallhaven.ANIME | Wallhaven.PEOPLE)) {
            count++;
        }
        if (!portrait) count++;
        if (adult) count++;
        return count;
    }
}
