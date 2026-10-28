package app.revanced.extension.soundcloud.settings;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A screen of rows under its own title: one of the sections the main screen lists. The same object serves the screen
 * itself and the search, which reads the rows of every section once, off screen.
 */
abstract class SectionScreen extends ScrollingScreen {
    SectionScreen(SettingsUi ui) {
        super(ui);
    }

    /** What the Activity is asked to open this section by. */
    abstract String id();

    /** The name of the section, also its row on the main screen. */
    abstract String title();

    /** What its row on the main screen says in the search; null for a section that has no row there. */
    String description() {
        return null;
    }

    /**
     * The short line under the section's name on the main screen, with the state of its settings ("Тема Алый").
     * Cheap: it is read every time the main screen is built. Anything slow goes to {@link #loadSummary}.
     */
    String summary() {
        return description();
    }

    /** Fills the summary line with something that takes time to find out (a size on disk); runs after the screen is shown. */
    void loadSummary(TextView summary) {
    }

    /** The key of the section's icon (see {@link SettingsIcons}). */
    String iconKey() {
        return "cog";
    }

    /** Words people use for the section that its name and description lack; the search finds the section by them. */
    String keywords() {
        return "";
    }

    /** Whether the search reads the rows of the section; the others are found by their row on the main screen. */
    boolean searched() {
        return true;
    }

    /** The rows of the section, below its title. */
    abstract void fill(LinearLayout list);

    @Override
    View create() {
        LinearLayout[] holder = new LinearLayout[1];
        LinearLayout root = createScreen(holder);
        LinearLayout list = holder[0];
        list.addView(ui.createSectionHeader(iconKey(), title(), description()));
        fill(list);
        return root;
    }
}
