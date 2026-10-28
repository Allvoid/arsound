package app.revanced.extension.soundcloud.settings;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * The list of a screen. A screen simply adds its rows one after another; the page puts every run of rows
 * ({@link CardItem}s) into a {@link SettingsGroup} card. A {@link GroupHeading} or any other view (a note, a hero card,
 * a list of results) ends the card, and the next row opens a new one. So a screen stays a flat list of
 * {@code list.addView(...)} calls and still gets the cards. Made by ScrollingScreen.createScreen.
 */
final class SettingsPage extends LinearLayout {
    private final SettingsUi ui;
    /** The card that takes the next row, null when the last view added was not a row. */
    private SettingsGroup open;

    SettingsPage(SettingsUi ui) {
        super(ui.activity);
        this.ui = ui;
        setOrientation(VERTICAL);
        setPadding(0, 0, 0, ui.dp(24));
    }

    /** A page inside a screen, for a list that is filled later (the tracks marked "not for me"): no room at the bottom. */
    static SettingsPage nested(SettingsUi ui) {
        SettingsPage page = new SettingsPage(ui);
        page.setPadding(0, 0, 0, 0);
        return page;
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        // An index means someone rearranges the page (the colour strips of a theme): leave it alone.
        if (index != -1) {
            super.addView(child, index, params);
            return;
        }
        if (child instanceof CardItem) {
            if (open == null || open.getParent() != this) {
                open = new SettingsGroup(ui);
                super.addView(open, -1, SettingsGroup.pageParams(ui));
            }
            open.addView(child, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        open = null;
        super.addView(child, index, params);
    }
}
