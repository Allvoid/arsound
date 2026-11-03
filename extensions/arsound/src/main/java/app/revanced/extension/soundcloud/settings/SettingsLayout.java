package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * How the main screen groups the sections: the order of the groups, their headings and which section stands in which.
 * What people change most comes first; set-once and rare things come last. Sections are named by their id; a section
 * that has a row but is not listed here falls into the last group, so a new section is never lost. Used by MainScreen.
 */
final class SettingsLayout {
    /** One group of section rows under a heading; a null heading means no heading (the last, quiet group). */
    static final class Group {
        private final String russian;
        private final String english;
        final List<String> sectionIds;

        Group(String russian, String english, String... sectionIds) {
            this.russian = russian;
            this.english = english;
            this.sectionIds = Collections.unmodifiableList(Arrays.asList(sectionIds));
        }

        /** The heading in the app language, or null for none. */
        String heading() {
            return russian == null ? null : text(russian, english);
        }
    }

    private SettingsLayout() {
    }

    static List<Group> groups() {
        return Arrays.asList(
                new Group("Главное", "Main", AppearanceScreen.ID, BottomBarScreen.ID, PlaybackScreen.ID, EqualizerScreen.ID),
                new Group("Музыка", "Music", MusicScreen.ID, RecommendationsScreen.ID, WaveScreen.ID, StatsScreen.ID),
                new Group("Данные", "Data", StorageScreen.ID, NetworkScreen.ID, AccountScreen.ID),
                new Group("Приложение", "App", AdsScreen.ID, PrivacyScreen.ID, UpdatesScreen.ID),
                new Group(null, null, DeveloperScreen.ID));
    }
}
