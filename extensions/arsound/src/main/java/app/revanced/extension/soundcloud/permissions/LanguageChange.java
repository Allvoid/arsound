package app.revanced.extension.soundcloud.permissions;

import android.app.Activity;
import android.app.LocaleManager;
import android.os.Build;
import android.os.LocaleList;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * Turns the whole app to the language picked on the greeting. Android rebuilds the screen for the new language, with
 * a moment of empty window in between. The screen fades into the theme colour first and the new one fades in from
 * it, so the change is one smooth blink.
 */
final class LanguageChange {
    /** Set while the screen is rebuilt for the language picked on the greeting: the new screen fades in. */
    private static boolean revealAfterLanguage;

    private LanguageChange() {
    }

    /** Android redraws the screen in the language by itself, a moment after the greeting is gone. */
    static void apply(Activity activity, boolean russian) {
        if (Build.VERSION.SDK_INT < 33) return;
        String language = russian ? "ru" : "en";
        if (language.equals(Locale.getDefault().getLanguage())) return;
        View veil = addVeil(activity, 0f);
        if (veil == null) return;
        veil.animate().alpha(1f).setStartDelay(120).setDuration(170).withEndAction(() -> {
            try {
                revealAfterLanguage = true;
                LocaleManager manager = activity.getSystemService(LocaleManager.class);
                manager.setApplicationLocales(LocaleList.forLanguageTags(language));
                Logger.printInfo(() -> "Welcome: app language " + language);
            } catch (Exception ex) {
                revealAfterLanguage = false;
                ((ViewGroup) veil.getParent()).removeView(veil);
                Logger.printException(() -> "Could not change the app language", ex);
            }
        }).start();
    }

    /** The main screen came back after the language change: fades it in. @return True if it did. */
    static boolean revealIfPending(Activity activity) {
        if (!revealAfterLanguage) return false;
        revealAfterLanguage = false;
        View veil = addVeil(activity, 1f);
        if (veil == null) return true;
        veil.animate().alpha(0f).setStartDelay(120).setDuration(280).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> ((ViewGroup) veil.getParent()).removeView(veil)).start();
        return true;
    }

    /** A cover of the theme colour over the whole window. */
    private static View addVeil(Activity activity, float alpha) {
        if (!(activity.getWindow().getDecorView() instanceof ViewGroup)) return null;
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        View veil = new View(activity);
        veil.setBackgroundColor(ArsoundTheme.palette(activity, "surface"));
        veil.setAlpha(alpha);
        // Taps during the blink go nowhere.
        veil.setClickable(true);
        decor.addView(veil, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return veil;
    }
}
