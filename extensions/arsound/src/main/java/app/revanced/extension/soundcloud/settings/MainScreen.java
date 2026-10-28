package app.revanced.extension.soundcloud.settings;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.revanced.extension.soundcloud.wallpaper.WallpaperStore;

/** The main screen: the search and the sections in groups, each section opens as its own screen. */
final class MainScreen extends ScrollingScreen {
    private final List<SectionScreen> sections;
    private final SettingsSearch search;

    MainScreen(SettingsUi ui) {
        super(ui);
        sections = SettingsScreens.sections(ui);
        search = new SettingsSearch(ui, this::addSectionRows, sections);
    }

    @Override
    View create() {
        LinearLayout[] holder = new LinearLayout[1];
        LinearLayout root = createScreen(holder);
        LinearLayout list = holder[0];
        // A theme may light the top of the main screen with its colour, fading into the background.
        int glow = ui.decor.glowColor();
        if (glow != 0) {
            GradientDrawable fade = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    // Over a wallpaper the glow fades into the picture, not into a slab of the surface colour.
                    new int[]{glow, WallpaperStore.isActive() ? ui.themeColor("themeColorSurface") & 0x00FFFFFF : ui.themeColor("themeColorSurface")});
            LayerDrawable background = new LayerDrawable(
                    new Drawable[]{ui.screenBackground(), fade});
            background.setLayerHeight(1, ui.dp(280));
            background.setLayerGravity(1, Gravity.TOP);
            root.setBackground(background);
        }
        list.addView(search.createHeader(createTitle("Arsound", true)));
        list.addView(search.sections());
        list.addView(search.results());
        addSectionRows(search.sections());
        ui.decor.addStrips(search.sections(), 0);
        return root;
    }

    /**
     * The section rows in groups under accent headings (see {@link SettingsLayout}). A section that has a row but is
     * in no group goes into the last one.
     */
    private void addSectionRows(LinearLayout list) {
        Map<String, SectionScreen> byId = new LinkedHashMap<>();
        for (SectionScreen section : sections) if (section.description() != null) byId.put(section.id(), section);

        List<SettingsLayout.Group> groups = SettingsLayout.groups();
        for (int i = 0; i < groups.size(); i++) {
            SettingsLayout.Group group = groups.get(i);
            String heading = group.heading();
            if (heading != null) list.addView(ui.createSubHeading(heading));
            else list.addView(ui.createGroupBreak());
            for (String id : group.sectionIds) {
                SectionScreen section = byId.remove(id);
                if (section != null) list.addView(ui.createSectionRow(section));
            }
            if (i == groups.size() - 1) {
                for (SectionScreen left : byId.values()) list.addView(ui.createSectionRow(left));
            }
        }
    }

    @Override
    boolean onBackPressed() {
        return search.closeIfOpen();
    }
}
