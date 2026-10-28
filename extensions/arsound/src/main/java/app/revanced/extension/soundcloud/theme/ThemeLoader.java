package app.revanced.extension.soundcloud.theme;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.content.res.loader.AssetsProvider;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.util.Collections;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.theme.ArsoundTheme.Theme;

/**
 * Puts the resource table of the chosen theme into the app's resources: one {@link ResourcesLoader} for the process,
 * which every screen gets added. The table is built by {@link ThemeTableBuilder} and kept on disk until the app is updated.
 * When the theme's colours change while the app runs (the wallpaper changed Monet's palette), a new table takes the
 * old one's place in the same loader, which updates every screen that already has it.
 */
final class ThemeLoader {
    /** How often the build of a theme is tried in one process; a failure that stays would be repeated for every screen. */
    private static final int MAX_BUILD_ATTEMPTS = 2;

    private final Context appContext;
    private volatile ResourcesLoader loader;
    /** The table the loader has now, to close it when the palette changes and a new one takes its place. */
    private ResourcesProvider loaded;
    /** The {@link Theme#key()} the loader's table was built for. */
    private volatile String loaderKey;
    private String failedKey;
    private int failedBuilds;

    ThemeLoader(Context appContext) {
        this.appContext = appContext;
    }

    /** The {@link Theme#key()} of the table the loader has, null before the first one. */
    String key() {
        return loaderKey;
    }

    /**
     * Adds the loader of this theme to these resources. When the theme's colours changed since the table was built,
     * the table is built again and put into the same loader.
     */
    synchronized void apply(Resources resources, Theme theme) throws Exception {
        if (loader == null || !theme.key().equals(loaderKey)) {
            // Each try is work on the main thread that gives no theme anyway; a later start tries again.
            if (theme.key().equals(failedKey) && failedBuilds >= MAX_BUILD_ATTEMPTS) return;
            ResourcesProvider provider;
            try {
                provider = provider(theme);
            } catch (Throwable ex) {
                failedBuilds = theme.key().equals(failedKey) ? failedBuilds + 1 : 1;
                failedKey = theme.key();
                throw ex;
            }
            failedKey = null;
            failedBuilds = 0;
            ResourcesProvider replaced = loaded;
            if (loader == null) {
                loader = new ResourcesLoader();
                loader.addProvider(provider);
            } else {
                loader.setProviders(Collections.singletonList(provider));
            }
            loaded = provider;
            loaderKey = theme.key();
            // The loader no longer uses the old table, but the descriptor and the mapping of it stay until the provider
            // is closed: one would be left behind for every change of the palette.
            if (replaced != null) {
                try {
                    replaced.close();
                } catch (Exception ex) {
                    Logger.printException(() -> "Theme: could not close the replaced resource table", ex);
                }
                // The table was swapped in a running process: the spinner may have been read already.
                LoadingAnimation.forgetCached();
            }
        }
        // Adding a loader that is already there changes nothing.
        resources.addLoaders(loader);
    }

    private ResourcesProvider provider(Theme theme) throws Exception {
        Context context = appContext;
        // The colours of a runtime theme are part of the file name: the table of the old colours may still be
        // mapped by the screens that were drawn with it, and rewriting a mapped file in place could crash them.
        String suffix = theme.isRuntime() ? "-" + Integer.toHexString(theme.key().hashCode()) : "";
        File file = new File(context.getCacheDir(), "arsound-theme-" + theme.id + suffix + ".arsc");
        // The resource table and the files it names are the same until the app is updated (or the player skin is
        // switched), so they are built once, not at every cold start. The stamp is written last: a build that
        // stopped half way leaves no stamp, and its files are not trusted.
        File stamp = new File(context.getFilesDir(), ThemeFiles.ROOT + "theme/" + theme.id + ".stamp");
        String key = cacheKey(context);
        if (key != null && theme.isRuntime()) key += "/" + theme.key();
        if (key != null && file.isFile() && key.equals(ThemeFiles.readText(stamp))) {
            try {
                return load(context, file);
            } catch (Exception ex) {
                // A damaged table is built again, not refused at every start.
                Logger.printException(() -> "Theme: the kept resource table was refused, building it again", ex);
            }
        }
        stamp.delete();
        // No loader yet in this process: the resources still show the app's own files.
        new ThemeTableBuilder(context, theme, loader == null).build(file);
        if (theme.isRuntime()) removeOldTables(context, theme, file);
        if (key != null) {
            try {
                ThemeFiles.write(stamp, key.getBytes("UTF-8"));
            } catch (Exception ex) {
                Logger.printException(() -> "Theme: could not remember the built theme", ex);
            }
        }
        return load(context, file);
    }

    /** Deletes the tables of the earlier colours of a runtime theme; a screen that still maps one keeps working. */
    private static void removeOldTables(Context context, Theme theme, File current) {
        File[] files = context.getCacheDir().listFiles();
        if (files == null) return;
        String prefix = "arsound-theme-" + theme.id + "-";
        for (File file : files) {
            if (file.getName().startsWith(prefix) && file.getName().endsWith(".arsc") && !file.equals(current)) file.delete();
        }
    }

    /** What the built files depend on: the installed app (the themes ship inside it) and the player skin switch. */
    private static String cacheKey(Context context) {
        try {
            long updated = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
            return updated + "/" + ArsoundTheme.isPlayerSkinEnabled();
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not read when the app was installed", ex);
            return null;
        }
    }

    /** The table in this file, with the fonts and pictures it names read from the files folder. */
    private static ResourcesProvider load(Context context, File file) throws Exception {
        File filesDir = context.getFilesDir();
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            return ResourcesProvider.loadFromTable(descriptor, new AssetsProvider() {
                @Override
                public AssetFileDescriptor loadAssetFd(String path, int accessMode) {
                    File asset = new File(filesDir, path.startsWith("res/arsound/") ? path.substring(4) : path);
                    if (!asset.isFile()) return null;
                    try {
                        return new AssetFileDescriptor(
                                ParcelFileDescriptor.open(asset, ParcelFileDescriptor.MODE_READ_ONLY), 0, asset.length());
                    } catch (Exception ex) {
                        return null;
                    }
                }
            });
        }
    }
}
