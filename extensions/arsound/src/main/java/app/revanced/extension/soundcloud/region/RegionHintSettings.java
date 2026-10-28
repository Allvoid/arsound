package app.revanced.extension.soundcloud.region;

import app.revanced.extension.soundcloud.shared.Lang;

/** The words of the switch in the Arsound settings that brings the hint back after "Don't show again". */
public final class RegionHintSettings {
    private RegionHintSettings() {
    }

    public static String title() {
        return RegionHintText.settingTitle(Lang.russian());
    }

    public static String description() {
        return RegionHintText.settingDescription(Lang.russian());
    }
}
