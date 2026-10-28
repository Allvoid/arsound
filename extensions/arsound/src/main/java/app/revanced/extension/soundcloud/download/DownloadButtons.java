package app.revanced.extension.soundcloud.download;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Reflect;

/**
 * SoundCloud's own download buttons: "Download" in the menu of a track, a playlist or an album, the button on the
 * track page, the round button on top of a playlist and of "Your likes".
 * <p>
 * They download for SoundCloud's offline mode, which needs a Go or Go+ subscription. Without one every button opens
 * the subscription offer, which Arsound hides, so a tap did nothing and people took downloading for broken. With the
 * setting on, the offer is replaced by the Arsound download of the same track, playlist or likes.
 * <p>
 * With a subscription SoundCloud downloads by itself and opens no offer, so its own download keeps working; the round
 * button, which Arsound sets up itself, is left to SoundCloud then ({@link #subscriptionDownloads}). The setting can
 * be turned off to get the original buttons back entirely.
 */
@SuppressWarnings("unused")
public final class DownloadButtons {
    private static final String UPGRADE_TARGET = "com.soundcloud.android.navigation.NavigationLinks$Target$Upgrade";

    /** SoundCloud's DefaultFeatureOperations, a single instance. */
    private static volatile java.lang.ref.WeakReference<Object> featureOperations = new java.lang.ref.WeakReference<>(null);

    private DownloadButtons() {
    }

    /** Injection point: the end of the constructor of SoundCloud's plan features. */
    public static void setFeatureOperations(Object operations) {
        featureOperations = new java.lang.ref.WeakReference<>(operations);
    }

    /**
     * True if the user's SoundCloud subscription includes downloads (Go, Go+): SoundCloud's own buttons work then,
     * and the ones Arsound takes over without an offer to replace (the round button on top of a list) stay its own.
     */
    static boolean subscriptionDownloads() {
        Object operations = featureOperations.get();
        if (operations == null) return false;
        try {
            return (boolean) operations.getClass().getMethod("isOfflineContentEnabled").invoke(operations);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not read whether the subscription includes downloads", ex);
            return false;
        }
    }

    /**
     * True if the round download button on top of a list is Arsound's: the setting is on and the subscription has no
     * downloads of its own. See {@link HeaderDownloadButton}.
     */
    static boolean interceptsHeaderButton() {
        return Settings.isInterceptDownloadButtonsEnabled() && !subscriptionDownloads();
    }

    /**
     * Injection point. The start of SoundCloud's navigator, which opens every screen.
     *
     * @return True if the screen is the subscription offer of a download button, which is replaced by the Arsound
     * download and must not open.
     */
    public static boolean interceptNavigation(Object target) {
        if (target == null || !Settings.isInterceptDownloadButtonsEnabled()
                || !UPGRADE_TARGET.equals(target.getClass().getName())) return false;
        try {
            String upsell = null;
            String urn = null;
            for (Object value : Reflect.instanceFieldValues(target)) {
                if (value instanceof Enum && value.getClass().getName().endsWith(".UpsellContext")) {
                    upsell = ((Enum<?>) value).name();
                } else if (urn == null && value != null && value.toString().startsWith("soundcloud:")) {
                    urn = value.toString();
                }
            }
            return handle(upsell, urn);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not read the subscription offer of a download button", ex);
            return false;
        }
    }

    private static boolean handle(String upsell, String urn) {
        if (upsell == null) return false;
        DownloadOffer offer = DownloadOffer.of(upsell);
        // The Downloads row of the library and SoundCloud's offline settings: not a download of something.
        return offer != null && offer.download(urn);
    }

    /**
     * Injection point. The "Download" item of the track menu shows a Go or Go+ badge when the download needs a
     * subscription; with Arsound behind the item it does not.
     */
    public static boolean showPlanBadge(boolean show) {
        return show && !Settings.isInterceptDownloadButtonsEnabled();
    }
}
