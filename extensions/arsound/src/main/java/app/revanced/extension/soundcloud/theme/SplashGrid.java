package app.revanced.extension.soundcloud.theme;

import java.util.List;
import java.util.Locale;

/**
 * The start screens baked for a theme whose colours are only known on the phone (the custom theme): one for each
 * pair of a background and a letter colour. The theme patch lists the colours in {@code assets/arsound/splash-grid.json}
 * and writes a style for each pair; this picks the pair nearest to the theme's real colours. No Android here.
 */
final class SplashGrid {
    /** The theme this grid is for. */
    final String themeId;
    /** printf pattern of a style's name, given the index of the background and the index of the letter colour. */
    private final String cell;
    private final int[] backgrounds, inks;

    private SplashGrid(String themeId, String cell, int[] backgrounds, int[] inks) {
        this.themeId = themeId;
        this.cell = cell;
        this.backgrounds = backgrounds;
        this.inks = inks;
    }

    /** @param backgrounds Colours as #RRGGBB, in the order the styles are numbered. */
    static SplashGrid of(String themeId, String cell, List<String> backgrounds, List<String> inks) {
        return new SplashGrid(themeId, cell, colors(backgrounds), colors(inks));
    }

    /** The name of the style whose background is nearest to the surface and whose letter is nearest to the accent. */
    String styleFor(int surface, int accent) {
        return String.format(Locale.ROOT, cell, SplashColors.nearest(backgrounds, surface), SplashColors.nearest(inks, accent));
    }

    private static int[] colors(List<String> hex) {
        int[] colors = new int[hex.size()];
        for (int i = 0; i < colors.length; i++) colors[i] = 0xFF000000 | Integer.parseInt(hex.get(i).substring(1), 16);
        return colors;
    }
}
