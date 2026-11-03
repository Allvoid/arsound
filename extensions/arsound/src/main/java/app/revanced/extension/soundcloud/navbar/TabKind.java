package app.revanced.extension.soundcloud.navbar;

import app.revanced.extension.soundcloud.settings.BoolSetting;
import app.revanced.extension.soundcloud.settings.BoolSettings;

/**
 * The tabs of SoundCloud's bottom bar the user may take out, found by the name of the class SoundCloud made the tab
 * from (those names are not shrunk). Library is not here: it always stays.
 */
public enum TabKind {
    HOME("Home", BoolSettings.HIDE_TAB_HOME),
    FEED("Feed", BoolSettings.HIDE_TAB_FEED),
    SEARCH("Search", BoolSettings.HIDE_TAB_SEARCH);

    private final String className;
    private final BoolSetting hidden;

    TabKind(String className, BoolSetting hidden) {
        this.className = className;
        this.hidden = hidden;
    }

    public BoolSetting hiddenSetting() {
        return hidden;
    }

    /** Whether the tab was made from a class with this simple name ({@code HomeNavigationTarget}, {@code DefaultFeedNavigationTarget}). */
    boolean isMadeFrom(String simpleName) {
        return simpleName.contains(className) && simpleName.endsWith("NavigationTarget");
    }

    /** The kind of a tab, or null for a tab the user cannot take out (Library, the create button). */
    static TabKind of(Object tab) {
        if (tab == null) return null;
        String name = tab.getClass().getSimpleName();
        for (TabKind kind : values()) if (kind.isMadeFrom(name)) return kind;
        return null;
    }

    boolean isHidden() {
        return hidden.get();
    }
}
