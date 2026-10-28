package app.revanced.extension.soundcloud.local;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Date;

/**
 * Makes the SoundCloud objects that stand for an imported file in the likes: its urn, a {@code Like} row for the
 * list and a {@code PlayItemInfo} for the play queue. Reflection, because the extension does not see the app's classes.
 */
final class LikeObjects {
    private static final String LOCAL_URN_SUFFIX = ".LocalTrackUrn";

    private static volatile Method getFile;

    private final ClassLoader loader;
    private final Constructor<?> likeConstructor;
    private final Constructor<?> playItemConstructor;

    LikeObjects(ClassLoader loader) throws ReflectiveOperationException {
        this.loader = loader;
        Class<?> urn = Class.forName("com.soundcloud.android.foundation.domain.Urn", false, loader);
        likeConstructor = Class.forName("com.soundcloud.android.foundation.domain.collections.Like", false, loader)
                .getConstructor(urn, Date.class, boolean.class);
        playItemConstructor = Class.forName("com.soundcloud.android.data.core.PlayItemInfo", false, loader)
                .getConstructor(urn, boolean.class);
    }

    /** The urn of the file, or null if the file is gone. */
    Object urn(File file) {
        return LocalAdditions.toUrn(loader, LocalAdditions.fileEntry(file));
    }

    /** A like of the file, made at the time the file was imported. */
    Object like(Object urn, File file) throws ReflectiveOperationException {
        return likeConstructor.newInstance(urn, new Date(file.lastModified()), false);
    }

    /** An entry of the play queue. */
    Object playItem(Object urn) throws ReflectiveOperationException {
        return playItemConstructor.newInstance(urn, false);
    }

    /** True for the urn of a file on this phone, as opposed to a SoundCloud track. */
    static boolean isLocal(Object urn) {
        return urn != null && urn.getClass().getName().endsWith(LOCAL_URN_SUFFIX);
    }

    /** The file of a local urn, or null for any other urn. */
    static File fileOf(Object urn) {
        if (!isLocal(urn)) return null;
        try {
            Method method = getFile;
            if (method == null) getFile = method = urn.getClass().getMethod("getFile");
            return (File) method.invoke(urn);
        } catch (ReflectiveOperationException | ClassCastException ex) {
            return null;
        }
    }
}
