package app.revanced.extension.soundcloud.network;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.AtomicFiles;

/**
 * Covers for when there is no network.
 * <p>
 * The cells of tracks take their covers from the image loaders' own caches, which keep little and forget it: without
 * a network most rows of a playlist were grey squares. Every cover that comes over the network is also kept here, and
 * a cover that cannot be downloaded is answered from here: the same picture, or the same cover in another size (a
 * small one is better than none).
 * <p>
 * Kept in the app's files, at most {@link #MAX_BYTES}; the covers used longest ago go first.
 */
final class OfflineArtwork {
    private static final String DIRECTORY = "arsound/artwork-offline";
    private static final long MAX_BYTES = 150L * 1024 * 1024;
    /** The folder is measured after this many new covers, not after each one. */
    private static final int TRIM_EVERY = 50;
    /** Between the name and the size in a file name: {@code artworks-abc-0@t120x120.jpg}. */
    private static final char SIZE_MARK = '@';

    private static int savedSinceTrim;

    private OfflineArtwork() {
    }

    /**
     * The cover's name and size from its address, such as {@code /artworks-abc-0-t500x500.jpg}: "artworks-abc-0",
     * "t500x500.jpg". Null when the address does not end in a size.
     */
    static String[] nameAndSize(String path) {
        int slash = path.lastIndexOf('/');
        String file = path.substring(slash + 1);
        int dash = file.lastIndexOf('-');
        if (dash <= 0 || dash == file.length() - 1 || file.indexOf(SIZE_MARK) >= 0) return null;
        String name = file.substring(0, dash);
        String size = file.substring(dash + 1);
        if (!size.matches("[a-z0-9]+(\\.[a-z0-9]+)?")) return null;
        return new String[]{name, size};
    }

    private static File folder() {
        Context context = Utils.getContext();
        if (context == null) return null;
        File folder = new File(context.getFilesDir(), DIRECTORY);
        return folder.isDirectory() || folder.mkdirs() ? folder : null;
    }

    /** Keeps a cover that came over the network. */
    static void keep(String path, byte[] bytes) {
        String[] parts = nameAndSize(path);
        File folder = folder();
        if (parts == null || folder == null || bytes == null || bytes.length == 0) return;
        File file = new File(folder, parts[0] + SIZE_MARK + parts[1]);
        try {
            if (file.isFile() && file.length() == bytes.length) {
                touch(file);
                return;
            }
            // The half-written file starts with a dot: the search for other sizes never finds it.
            AtomicFiles.write(new File(folder, "." + file.getName() + "." + Thread.currentThread().getId()), file, bytes);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Offline covers: could not keep " + path + ": " + ex);
            return;
        }
        boolean trim;
        synchronized (OfflineArtwork.class) {
            trim = ++savedSinceTrim >= TRIM_EVERY;
            if (trim) savedSinceTrim = 0;
        }
        if (trim) {
            // Off the request: the cover is answered without waiting for the folder to be sorted and cut.
            Utils.runOnBackgroundThread(() -> {
                try {
                    trim(folder);
                } catch (RuntimeException ex) {
                    Logger.printException(() -> "Offline covers: could not trim the folder", ex);
                }
            });
        }
    }

    /** The kept cover for this address, the same size if there is one, else the biggest other size. Null if none. */
    static File find(String path) {
        String[] parts = nameAndSize(path);
        File folder = folder();
        if (parts == null || folder == null) return null;
        File exact = new File(folder, parts[0] + SIZE_MARK + parts[1]);
        if (exact.isFile()) return exact;
        String prefix = parts[0] + SIZE_MARK;
        File[] others = folder.listFiles((dir, name) -> name.startsWith(prefix));
        if (others == null || others.length == 0) return null;
        return Arrays.stream(others).max(Comparator.comparingLong(File::length)).orElse(null);
    }

    /**
     * An answer made from a kept cover, for a request the network could not serve.
     *
     * @param request The {@code okhttp3.Request}.
     * @return The {@code okhttp3.Response}, or null if no cover is kept.
     */
    static Object answer(String path, Object request) {
        File file = find(path);
        if (file == null) return null;
        try {
            byte[] bytes;
            try (InputStream input = new FileInputStream(file)) {
                bytes = readAll(input, (int) file.length());
            }
            touch(file);
            boolean sameSize = file.getName().endsWith(SIZE_MARK + nameAndSize(path)[1]);
            Object response = response(request, bytes, file.getName().endsWith(".png") ? "image/png" : "image/jpeg",
                    // Another size stands in only until the network is back: the loaders ask again then.
                    sameSize ? "public, max-age=86400" : "no-cache");
            Logger.printInfo(() -> "Offline covers: answered " + path + " from " + file.getName());
            return response;
        } catch (Throwable ex) {
            Logger.printInfo(() -> "Offline covers: could not answer " + path + ": " + ex);
            return null;
        }
    }

    private static byte[] readAll(InputStream input, int size) throws IOException {
        byte[] bytes = new byte[size];
        int read = 0;
        while (read < size) {
            int count = input.read(bytes, read, size - read);
            if (count < 0) throw new IOException("The file ended early");
            read += count;
        }
        return bytes;
    }

    /** A fresh {@code okhttp3.Response} with the picture. OkHttp is not obfuscated: found by name. */
    private static Object response(Object request, byte[] bytes, String type, String cacheControl) throws Throwable {
        ClassLoader loader = request.getClass().getClassLoader();
        Class<?> requestClass = Class.forName("okhttp3.Request", false, loader);
        Class<?> builderClass = Class.forName("okhttp3.Response$Builder", false, loader);
        Class<?> protocolClass = Class.forName("okhttp3.Protocol", false, loader);
        Class<?> bodyClass = Class.forName("okhttp3.ResponseBody", false, loader);
        Class<?> mediaTypeClass = Class.forName("okhttp3.MediaType", false, loader);
        Object mediaType = mediaTypeClass.getMethod("parse", String.class).invoke(null, type);
        Object body;
        try {
            body = bodyClass.getMethod("create", byte[].class, mediaTypeClass).invoke(null, bytes, mediaType);
        } catch (NoSuchMethodException ex) {
            body = bodyClass.getMethod("create", mediaTypeClass, byte[].class).invoke(null, mediaType, bytes);
        }
        Object builder = builderClass.getConstructor().newInstance();
        builderClass.getMethod("request", requestClass).invoke(builder, request);
        builderClass.getMethod("protocol", protocolClass).invoke(builder, protocolClass.getField("HTTP_1_1").get(null));
        builderClass.getMethod("code", int.class).invoke(builder, 200);
        builderClass.getMethod("message", String.class).invoke(builder, "OK");
        builderClass.getMethod("header", String.class, String.class).invoke(builder, "Cache-Control", cacheControl);
        builderClass.getMethod("body", bodyClass).invoke(builder, body);
        return builderClass.getMethod("build").invoke(builder);
    }

    /** Marks a cover as used now, so the trim keeps it. */
    private static void touch(File file) {
        //noinspection ResultOfMethodCallIgnored
        file.setLastModified(System.currentTimeMillis());
    }

    /** Deletes the covers used longest ago until the folder fits. */
    private static void trim(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        long total = 0;
        for (File file : files) total += file.length();
        if (total <= MAX_BYTES) return;
        // The times are read once: another thread touches covers it answers from disk, and a key that changes during
        // the sort breaks its contract ("Comparison method violates its general contract"), which killed the process.
        long[] times = new long[files.length];
        Integer[] order = new Integer[files.length];
        for (int i = 0; i < files.length; i++) {
            times[i] = files[i].lastModified();
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(times[a], times[b]));
        File[] sorted = new File[files.length];
        for (int i = 0; i < order.length; i++) sorted[i] = files[order[i]];
        files = sorted;
        int deleted = 0;
        for (File file : files) {
            if (total <= MAX_BYTES * 9 / 10) break;
            long length = file.length();
            if (file.delete()) {
                total -= length;
                deleted++;
            }
        }
        int count = deleted;
        Logger.printInfo(() -> "Offline covers: " + count + " old covers deleted");
    }
}
