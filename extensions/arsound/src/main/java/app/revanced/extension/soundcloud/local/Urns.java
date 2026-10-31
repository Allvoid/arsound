package app.revanced.extension.soundcloud.local;

import java.io.File;

import app.revanced.extension.shared.Logger;

/**
 * SoundCloud's {@code Urn} objects: finding and making them by name, as the classes are shrunk but their names
 * and methods that are named here stay.
 */
final class Urns {
    private static final String URN_CLASS = "com.soundcloud.android.foundation.domain.Urn";
    private static final String LOCAL_TRACK_URN_CLASS = "com.soundcloud.android.foundation.domain.LocalTrackUrn";
    private static final String LOCAL_TRACK_URN_SUFFIX = ".LocalTrackUrn";

    private Urns() {
    }

    /** The urn of an item such as a playlist or a track item ({@code getUrn()}), as SoundCloud's object. */
    static Object of(Object item) throws Exception {
        return app.revanced.extension.soundcloud.shared.Getters.get(item, "getUrn");
    }

    /** The urn of an item as text, e.g. {@code soundcloud:playlists:123}. */
    static String textOf(Object item) throws Exception {
        return String.valueOf(of(item));
    }

    /** The same, or null if the item has no readable urn. */
    static String textOrNull(Object item) {
        try {
            return textOf(item);
        } catch (Exception ex) {
            return null;
        }
    }

    static Class<?> urnClass(ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(URN_CLASS, false, loader);
    }

    /** The factories of urns, looked up once: a list of a thousand files made a lookup per file. */
    private static volatile java.lang.reflect.Method forTrackMethod, fromFileMethod;
    private static volatile Object localUrnCompanion;

    static Object forTrack(ClassLoader loader, String id) throws Exception {
        java.lang.reflect.Method method = forTrackMethod;
        if (method == null) forTrackMethod = method = urnClass(loader).getMethod("forTrack", String.class);
        return method.invoke(null, id);
    }

    static Object forPlaylist(ClassLoader loader, String id) throws Exception {
        return urnClass(loader).getMethod("forPlaylist", String.class).invoke(null, id);
    }

    /** The urn of an imported file, which SoundCloud's player resolves with metadata read from the file. */
    static Object forFile(ClassLoader loader, File file) throws Exception {
        java.lang.reflect.Method method = fromFileMethod;
        Object companion = localUrnCompanion;
        if (method == null || companion == null) {
            Class<?> localUrn = Class.forName(LOCAL_TRACK_URN_CLASS, false, loader);
            companion = localUrn.getField("Companion").get(null);
            method = companion.getClass().getMethod("fromFile", File.class);
            localUrnCompanion = companion;
            fromFileMethod = method;
        }
        return method.invoke(companion, file);
    }

    /** True for the urn of an imported file. */
    static boolean isLocalFile(Object urn) {
        return urn != null && urn.getClass().getName().endsWith(LOCAL_TRACK_URN_SUFFIX);
    }

    /** The imported file of a local file urn. */
    static File fileOf(Object localFileUrn) throws Exception {
        return (File) localFileUrn.getClass().getMethod("getFile").invoke(localFileUrn);
    }

    /**
     * The track menu gets an imported file as a plain urn, "sc-local:tracks:local_" and the path in Base64.
     *
     * @return The file of an imported track, or null for any other urn.
     */
    static File menuFileOf(Object trackUrn) {
        String urn = String.valueOf(trackUrn);
        int start = urn.indexOf(":local_");
        if (!urn.startsWith("sc-local:") || start < 0) return null;
        try {
            byte[] path = java.util.Base64.getUrlDecoder().decode(urn.substring(start + ":local_".length()));
            return new File(new String(path, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            Logger.printException(() -> "Not a local track urn: " + urn, ex);
            return null;
        }
    }

    /** The id after the last colon of an urn text: {@code soundcloud:playlists:123} gives {@code 123}. */
    static String idOf(String urnText) {
        return urnText.substring(urnText.lastIndexOf(':') + 1);
    }
}
