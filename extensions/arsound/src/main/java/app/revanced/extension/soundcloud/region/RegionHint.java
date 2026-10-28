package app.revanced.extension.soundcloud.region;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.AppResources;

/**
 * A "?" next to the label "Not available in your country" on tracks, which explains the label and what helps.
 * <p>
 * SoundCloud draws the line of labels under a track title in two ways, a View ({@code MetaLabel}) and a Compose
 * one, both from the same list of label types. The patch tells this class that list first, and when the line is
 * about to be built, asks for the badge to be added at its end. The banner at the top of the track page is a third
 * place and gets its badge directly.
 */
@SuppressWarnings("unused")
public final class RegionHint {
    private static final String LOCK_ICON = "ic_labels_geo_locked";

    /**
     * Whether the label line being built holds the label. Set by {@link #noteLabels} and used by the call that
     * follows it in the same function, so it never outlives one line.
     */
    private static final ThreadLocal<Boolean> LINE_HAS_LABEL = new ThreadLocal<>();

    private static volatile int lockIcon;

    private RegionHint() {
    }

    static boolean isEnabled() {
        return Settings.isRegionHintEnabled();
    }

    /** "Don't show again" was ticked: no badge is shown from now on, and those on screen go. */
    static void turnOff(boolean russian) {
        Settings.putBoolean(Settings.REGION_HINT, false);
        RegionHintBadge.hideAll();
        try {
            Utils.showToastShort(RegionHintText.hiddenToast(russian));
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not say that the hint is hidden", ex);
        }
    }

    private static int lockIcon() {
        int icon = lockIcon;
        if (icon == 0) {
            Context context = Utils.getContext();
            if (context != null) lockIcon = icon = AppResources.identifier(context, LOCK_ICON, "drawable");
        }
        return icon;
    }

    /**
     * Injection point. Called with the label types of a line, before the line is built.
     */
    public static void noteLabels(List<?> labels) {
        boolean found = false;
        try {
            found = isEnabled() && GeoLabel.contains(labels, lockIcon());
        } catch (Exception ex) {
            Logger.printException(() -> "noteLabels failure", ex);
        }
        LINE_HAS_LABEL.set(found);
    }

    private static boolean takeLineHasLabel() {
        boolean found = Boolean.TRUE.equals(LINE_HAS_LABEL.get());
        LINE_HAS_LABEL.set(false);
        return found && isEnabled();
    }

    /**
     * Injection point. Called with the pieces of a Compose label line, right before it is laid out.
     *
     * @return The pieces, with the badge at the end if the line holds the label.
     */
    public static List<Object> appendBadge(List<Object> pieces) {
        try {
            if (!takeLineHasLabel()) return pieces;
            List<Object> withBadge = new ArrayList<>(pieces);
            withBadge.add(ComposeBadge.INSTANCE);
            return withBadge;
        } catch (Exception ex) {
            Logger.printException(() -> "appendBadge failure", ex);
            return pieces;
        }
    }

    /**
     * Injection point. Called with the views of a View label line, right before they are put in the line.
     *
     * @return The views, with the badge at the end if the line holds the label.
     */
    public static List<View> appendBadgeView(List<View> views) {
        try {
            if (views.isEmpty() || !takeLineHasLabel()) return views;
            View badge = new RegionHintBadge(views.get(0).getContext());
            badge.setId(View.generateViewId());
            badge.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            List<View> withBadge = new ArrayList<>(views);
            withBadge.add(badge);
            return withBadge;
        } catch (Exception ex) {
            Logger.printException(() -> "appendBadgeView failure", ex);
            return views;
        }
    }

    /**
     * Injection point. Called in the row of the banner at the top of a track page, after its text.
     */
    public static void bannerBadge(Composer composer) {
        try {
            if (isEnabled()) ComposeBadge.INSTANCE.invoke(Modifier.Companion, composer, 0);
        } catch (Exception ex) {
            Logger.printException(() -> "bannerBadge failure", ex);
        }
    }
}
