package app.revanced.extension.soundcloud.wallpaper;

/**
 * Small decisions of the Wallhaven browser that are plain arithmetic: how many columns the grid has, how tall a cell is,
 * how many filters differ from the usual, which sorting a search really uses and when the tall pictures are used up.
 * Pure rules with no Android, used by {@code WallhavenScreen}, {@link Wallhaven} and {@link WallhavenRequest}.
 */
public final class WallhavenRules {
    /** A column is at least this wide, in dp, so a thumbnail is not too small to judge. */
    private static final int COLUMN_DP = 120;

    /**
     * The sorting of a browse with no text: every picture by the number of people who saved it. Measured on the guest
     * API for tall pictures, the top list of a month holds a few hundred and the top list of a year a few thousand,
     * while this one holds about 78 000, and its hundredth page still has pictures that 150 people saved.
     */
    public static final String DEFAULT_SORTING = Wallhaven.FAVORITES;

    /**
     * The smallest tall picture asked for, in pixels. A tall picture is cut to the screen by covering it, and nearly all
     * tall pictures on the service are at least 1920 high anyway, so a lower floor adds only a few more (the width is what
     * a phone needs).
     */
    public static final int TALL_MIN_WIDTH = 1080, TALL_MIN_HEIGHT = 1440;
    /**
     * The smallest wide picture that follows when the tall ones are used up. Only the middle of it is shown on a phone,
     * so it has to be big: from a 2560x1440 picture the middle of a phone's shape is 648 pixels wide and 1440 high, which
     * a phone fills without much stretching.
     */
    public static final int WIDE_FOR_PHONE_MIN_WIDTH = 2560, WIDE_FOR_PHONE_MIN_HEIGHT = 1440;
    /** The smallest picture when the user chose wide pictures by hand. */
    public static final int WIDE_MIN_WIDTH = 1920, WIDE_MIN_HEIGHT = 1080;

    /** The shape of picture a search asks for. */
    public enum Shape {
        /** Tall pictures, which fit a phone held upright. */
        TALL,
        /** Wide pictures that follow the tall ones when those are used up; only their middle is shown. */
        WIDE_FOR_PHONE,
        /** Wide pictures the user chose by hand. */
        WIDE
    }

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

    // region Search

    /**
     * The sorting a search really uses. A sorting the user picked by hand is kept, with a text too. Otherwise the app
     * picks: a text is looked for by relevance, because the top list of a period holds only the few pictures that were
     * popular then ("space" found none in a month's top list, 473 tall ones by relevance), and a browse with no text
     * is by {@link #DEFAULT_SORTING}.
     *
     * @param chosen True when the user tapped a sorting chip.
     */
    public static String sorting(String text, String sorting, boolean chosen) {
        if (chosen) return sorting;
        boolean typed = text != null && !text.trim().isEmpty();
        return typed ? Wallhaven.RELEVANCE : DEFAULT_SORTING;
    }

    /**
     * Whether a sorting saved by an older version was picked by hand. Those versions saved the sorting always, so the
     * two ways they started a search count as the app's own: the top list of a month (the old start) and relevance
     * with a text (the old switch for a text). Anything else was a tap on a chip.
     */
    public static boolean legacySortingWasChosen(String sorting, String range, boolean hasText) {
        if (Wallhaven.TOPLIST.equals(sorting) && "1M".equals(range)) return false;
        return !(Wallhaven.RELEVANCE.equals(sorting) && hasText);
    }

    /** The shape to ask for: wide when the user chose it, else tall, and wide-for-a-phone once the tall ones ran out. */
    public static Shape shape(boolean portrait, boolean widened) {
        if (!portrait) return Shape.WIDE;
        return widened ? Shape.WIDE_FOR_PHONE : Shape.TALL;
    }

    /**
     * True when the page just read was the last one of the tall pictures and wide ones may follow in the same grid, so a
     * search that finds few tall pictures (a rare word finds none) still fills the screen.
     */
    public static boolean tallRanOut(boolean portrait, boolean widened, int page, int lastPage) {
        return portrait && !widened && page >= lastPage;
    }

    /** True for a picture whose resolution ("1920x1080") is wider than it is tall; false when it cannot be read. */
    public static boolean isWide(String resolution) {
        if (resolution == null) return false;
        int cross = resolution.indexOf('x');
        if (cross <= 0 || cross >= resolution.length() - 1) return false;
        try {
            return Integer.parseInt(resolution.substring(0, cross).trim()) > Integer.parseInt(resolution.substring(cross + 1).trim());
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** A size as people read it, for the words of the filters: {@code 1080×1440}. */
    public static String size(int width, int height) {
        return width + "×" + height;
    }

    // endregion
}
