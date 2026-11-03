package com.soundcloud.android.foundation.navigation;

import androidx.fragment.app.Fragment;

import com.soundcloud.android.foundation.domain.Screen;

/** The tabs of SoundCloud's bottom bar. Arsound adds a tab of its own ({@code navbar.WaveNavigationTarget}). */
public class NavigationModel {
    /** One tab: its title (a string resource), its icon (a drawable resource) and the screen it stands for. */
    public abstract static class Target {
        public abstract int getIcon();

        public abstract int getName();

        public abstract Screen getScreen();

        /** A tab with a page of its own, which the bar makes once and keeps. */
        public abstract static class Selectable extends Target {
            public abstract Fragment createFragment();

            public abstract String getTrackingName();
        }
    }
}
