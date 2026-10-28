package app.revanced.extension.soundcloud.theme;

import android.content.res.Resources;

import app.revanced.extension.shared.Logger;

/** Looks up SoundCloud's resource ids by name and adds overrides for them to the resource table that is being built. */
final class ResourceOverrides {
    private final ArscWriter table;
    private final Resources resources;
    private final String packageName;

    /** @param packageName The package the resources are found under: the app's, or SoundCloud's when it is installed under another name. */
    ResourceOverrides(ArscWriter table, Resources resources, String packageName) {
        this.table = table;
        this.resources = resources;
        this.packageName = packageName;
    }

    /** The id of the resource in the app, 0 (and a note in the log) when it has none. */
    int id(String type, String name) {
        int id = resources.getIdentifier(name, type, packageName);
        if (id == 0) Logger.printInfo(() -> "Theme: no " + type + " " + name);
        return id;
    }

    /** A colour in the default configuration and, when given, another one in the night configuration. */
    void color(String name, int normal, Integer night) {
        int id = id("color", name);
        if (id == 0) return;
        table.put(id, "color", name, ArscWriter.Value.color(normal), false);
        if (night != null) table.put(id, "color", name, ArscWriter.Value.color(night), true);
    }

    void dimen(String name, int dp) {
        int id = id("dimen", name);
        if (id != 0) table.put(id, "dimen", name, ArscWriter.Value.dp(dp), false);
    }

    /** Points SoundCloud's resource at another one of the same type, in both modes. */
    void alias(String type, String name, String replacement) {
        int id = id(type, name);
        int target = id(type, replacement);
        if (id == 0 || target == 0) return;
        table.put(id, type, name, ArscWriter.Value.reference(target), false);
        table.put(id, type, name, ArscWriter.Value.reference(target), true);
    }

    /** A file the table names by its path under res/ (a font). */
    void file(String type, String name, String path) {
        int id = id(type, name);
        if (id != 0) table.put(id, type, name, ArscWriter.Value.file(path), false);
    }
}
