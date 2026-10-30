package app.revanced.extension.soundcloud.settings;

import java.util.Arrays;
import java.util.List;

/** The screens of the settings and how they are told apart. */
final class SettingsScreens {
    private SettingsScreens() {
    }

    /** The screen an Activity was asked to show: the main one for no id, a section otherwise. */
    static SettingsScreen open(SettingsUi ui, String id) {
        if (id == null) return new MainScreen(ui);
        if (LocalMusicScreen.ID.equals(id)) return new LocalMusicScreen(ui);
        if (WallhavenScreen.ID.equals(id)) return new WallhavenScreen(ui);
        if (TransferScreen.ID.equals(id)) return new TransferScreen(ui);
        for (SectionScreen section : sections(ui)) {
            if (section.id().equals(id)) return section;
        }
        return new DeveloperScreen(ui);
    }

    /**
     * Every section, in the order of the main screen (see {@link SettingsLayout}); the search reads those that say so, in
     * this order, and equal scores keep it.
     */
    static List<SectionScreen> sections(SettingsUi ui) {
        return Arrays.asList(
                new AppearanceScreen(ui),
                new CustomThemeScreen(ui),
                new PlaybackScreen(ui),
                new EqualizerScreen(ui),
                new MusicScreen(ui),
                new RecommendationsScreen(ui),
                new StatsScreen(ui),
                new StorageScreen(ui),
                new NetworkScreen(ui),
                new AccountScreen(ui),
                new AdsScreen(ui),
                new PrivacyScreen(ui),
                new UpdatesScreen(ui),
                new DeveloperScreen(ui),
                new AppIconScreen(ui));
    }
}
