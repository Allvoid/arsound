package app.revanced.extension.soundcloud.wallpaper;

/**
 * Looks at a class by the names in its line of parents. SoundCloud's own views and the libraries' (the app bar, the
 * lists) are known here by name only, so the extension needs no compile dependency on them.
 * <p>
 * Pure logic, without Android, so it is tested on the JVM.
 */
final class ClassNames {
    private ClassNames() {
    }

    /** True if the class or one of its parents has one of the simple names (the name without its package). */
    static boolean hasParentNamed(Class<?> type, String... simpleNames) {
        for (Class<?> parent = type; parent != null; parent = parent.getSuperclass()) {
            String name = parent.getSimpleName();
            for (String wanted : simpleNames) if (wanted.equals(name)) return true;
        }
        return false;
    }
}
