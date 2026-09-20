package app.revanced.extension.soundcloud.library;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

@SuppressWarnings("unused")
public final class HideImportBannerPatch {
    /** The state of the playlist import banner that draws nothing. */
    private static final String DISABLED = "DISABLED";

    private HideImportBannerPatch() {
    }

    /**
     * Injection point. Called with the state SoundCloud picked for the playlist import banner
     * ("Transfer your gems") before it reaches the library.
     * <p>
     * The banner is stopped here rather than in its renderers: the library then never builds
     * an item for it, so nothing is left behind and the banner cannot come back on its own
     * once its dismissal expires.
     *
     * @return The disabled state while the banner is hidden, otherwise the original state.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object filterImportBannerState(Object state) {
        try {
            if (!(state instanceof Enum) || !Settings.isHideImportBannerEnabled()) return state;

            Class type = ((Enum<?>) state).getDeclaringClass();
            return Enum.valueOf(type, DISABLED);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not hide the playlist import banner", ex);
            return state;
        }
    }
}
