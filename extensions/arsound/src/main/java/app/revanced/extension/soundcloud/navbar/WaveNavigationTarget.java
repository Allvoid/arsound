package app.revanced.extension.soundcloud.navbar;

import androidx.fragment.app.Fragment;

import com.soundcloud.android.foundation.domain.Screen;
import com.soundcloud.android.foundation.navigation.NavigationModel;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.recommendations.WavePage;

/**
 * The "My Wave" tab of the bottom bar: a tab like SoundCloud's own, put into the list of tabs by {@link BottomTabs}.
 * The bar takes its title and icon from here, and makes its page ({@link WavePage}) once and keeps it.
 * <p>
 * Two tabs are the same tab for SoundCloud when their screen, title, icon and tracking name agree; every instance of
 * this class agrees with every other, so a list built again compares equal to the one before. The screen is one that
 * no SoundCloud screen opens any more, so nothing else selects this tab by it.
 */
public final class WaveNavigationTarget extends NavigationModel.Target.Selectable {
    private static int icon, name;

    @Override
    public Fragment createFragment() {
        return new WavePage();
    }

    @Override
    public int getIcon() {
        if (icon == 0) icon = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_tab_wave");
        return icon;
    }

    @Override
    public int getName() {
        if (name == 0) name = Utils.getResourceIdentifier(ResourceType.STRING, "arsound_tab_wave");
        return name;
    }

    @Override
    public Screen getScreen() {
        return Screen.HIGHLIGHTS;
    }

    @Override
    public String getTrackingName() {
        return "navbar:arsound_wave";
    }
}
