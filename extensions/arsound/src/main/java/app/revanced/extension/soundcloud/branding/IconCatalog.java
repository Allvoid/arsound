package app.revanced.extension.soundcloud.branding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The app icon variants there are: Arsound's own ({@link AppIconList}), then SoundCloud's ({@link SoundCloudIconList}).
 * A row is id, name, English name, group, English group; the first row is the default.
 */
final class IconCatalog {
    private static volatile List<String[]> rows;

    private IconCatalog() {
    }

    /**
     * The Arsound variants, then SoundCloud's own icons. A SoundCloud icon the patched version did not have has no
     * picture and no launcher entry, so it is left out.
     */
    static List<String[]> rows() {
        List<String[]> known = rows;
        if (known != null) return known;
        List<String[]> all = new ArrayList<>(Arrays.asList(AppIconList.ICONS));
        for (String[] row : SoundCloudIconList.ICONS) {
            if (IconArt.hasPicture(row[0])) all.add(row);
        }
        rows = all;
        return all;
    }

    /** The variant the manifest enables, which the user never changed. */
    static String defaultId() {
        return AppIconList.ICONS[0][0];
    }

    static List<AppIcons.Icon> icons() {
        List<AppIcons.Icon> icons = new ArrayList<>();
        for (String[] row : rows()) icons.add(new AppIcons.Icon(row));
        return icons;
    }
}
