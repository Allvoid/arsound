package app.revanced.extension.soundcloud.update;

/**
 * The order of two release tags. Only the dotted numbers before the first "-" or "+" are compared; a tag with a
 * suffix ("0.9.3-rc.1") comes before the same numbers without one. The digits of a suffix were read as one more part
 * of the version, so a release candidate looked newer than the release it led to.
 */
final class ReleaseVersion {
    private ReleaseVersion() {
    }

    /** @return Below zero if {@code first} is older, zero if they are the same, above zero if it is newer. */
    static int compare(String first, String second) {
        String[] a = numbers(first).split("[^0-9]+");
        String[] b = numbers(second).split("[^0-9]+");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length && !a[i].isEmpty() ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && !b[i].isEmpty() ? Integer.parseInt(b[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        // The same numbers: a pre-release comes before the release.
        return Boolean.compare(!hasSuffix(first), !hasSuffix(second));
    }

    /** The part before the first "-" or "+". */
    private static String numbers(String version) {
        int end = suffixStart(version);
        return end < 0 ? version : version.substring(0, end);
    }

    private static boolean hasSuffix(String version) {
        int start = suffixStart(version);
        return start >= 0 && start < version.length() - 1;
    }

    private static int suffixStart(String version) {
        int dash = version.indexOf('-');
        int plus = version.indexOf('+');
        if (dash < 0) return plus;
        if (plus < 0) return dash;
        return Math.min(dash, plus);
    }
}
