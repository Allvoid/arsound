package app.revanced.extension.soundcloud.wallpaper;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.revanced.extension.soundcloud.shared.AppResources;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * One built SoundCloud main activity as far as the wallpaper is concerned: whether it was built with the wallpaper
 * behind it, and which views are its main screens (the tabs and every screen pushed over them, which SoundCloud puts
 * into the container {@code main_container}; the player, the mini player, the tab bar and the dialogs are not in it).
 */
final class MainScreen {
    private static final Map<Activity, MainScreen> screens = Collections.synchronizedMap(new WeakHashMap<>());

    /** Weak: the map holds this object for as long as the activity lives, and a strong link would keep it alive. */
    private final WeakReference<Activity> activity;
    private final boolean wallpaper;
    private volatile SurfaceColours surfaces;
    private WeakReference<View> container = new WeakReference<>(null);

    private MainScreen(Activity activity, boolean wallpaper) {
        this.activity = new WeakReference<>(activity);
        this.wallpaper = wallpaper;
    }

    static boolean isMain(Activity activity) {
        return activity.getClass().getName().endsWith(".MainActivity");
    }

    /** Remembers the activity that is being built, with or without the wallpaper. */
    static MainScreen register(Activity activity, boolean wallpaper) {
        MainScreen screen = new MainScreen(activity, wallpaper);
        screens.put(activity, screen);
        return screen;
    }

    /** The main activity the view or context belongs to, or null for any other (a dialog, Arsound's own screens). */
    static MainScreen of(Context context) {
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        return context instanceof Activity ? screens.get(context) : null;
    }

    /** True if the activity was built with the wallpaper: its window carries the picture. */
    boolean hasWallpaper() {
        return wallpaper;
    }

    /** True if the view is on a main screen: below {@code main_container} of this activity's window. */
    boolean contains(View view) {
        View main = mainContainer();
        if (main == null) return false;
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == main) return true;
        }
        return false;
    }

    /** Clears the base layers of a screen's views if the view is on a main screen. */
    void relaxIfOnMainScreen(View root) {
        if (!contains(root)) return;
        SurfaceColours colours = surfaces;
        if (colours == null) surfaces = colours = resolveSurfaces(root.getContext());
        ScreenBackgrounds.relax(root, colours);
    }

    /**
     * The container of the main screens. The home screen's layout has a container with the same id inside it, but the
     * activity's comes first when the layout is searched from the top.
     */
    private View mainContainer() {
        View main = container.get();
        if (main != null && main.getParent() != null) return main;
        Activity owner = activity.get();
        if (owner == null) return null;
        main = owner.findViewById(AppResources.identifier(owner, "main_container", "id"));
        container = new WeakReference<>(main);
        return main;
    }

    /**
     * The colours SoundCloud fills a screen with, in the look drawn now: the theme's surface, the names it has in the
     * styles and the surface of the phone's mode. Read when the first screen is built, as the theme is final by then.
     */
    private static SurfaceColours resolveSurfaces(Context context) {
        List<Integer> colours = new ArrayList<>();
        colours.add(attribute(context, AppResources.identifier(context, "themeColorSurface", "attr")));
        colours.add(attribute(context, AppResources.identifier(context, "colorSurface", "attr")));
        colours.add(attribute(context, android.R.attr.colorBackground));
        int mode = AppResources.identifier(context, ArsoundTheme.isNight(context) ? "dark_mode_surface" : "light_mode_surface", "color");
        colours.add(mode == 0 ? null : context.getColor(mode));
        colours.add(ArsoundTheme.palette(context, "surface"));
        colours.removeAll(Collections.singleton(null));
        int[] others = new int[colours.size() - 1];
        for (int i = 0; i < others.length; i++) others[i] = colours.get(i + 1);
        return new SurfaceColours(colours.get(0), others);
    }

    /** A colour of the theme by attribute, or null if the theme has none under that name. */
    private static Integer attribute(Context context, int attribute) {
        TypedValue value = new TypedValue();
        boolean colour = attribute != 0 && context.getTheme().resolveAttribute(attribute, value, true)
                && value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT;
        return colour ? value.data : null;
    }
}
