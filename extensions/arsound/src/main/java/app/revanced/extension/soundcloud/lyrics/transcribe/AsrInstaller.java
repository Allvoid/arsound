package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import app.revanced.extension.shared.Logger;

/**
 * Puts the native library and the speech model on the phone, and takes them off. Everything lives in
 * {@code files/arsound/asr}: the library in a folder named after its version (a new version is a new folder, the old
 * one is removed), each model in a folder of its own. Nothing is ever kept as a half-file under its final name, so
 * "the file is there with the right size" means "it was downloaded whole and its checksum was right".
 * <p>
 * Called by {@link TranscriptionJob} (install what is missing) and by the settings rows through
 * {@link TranscriptionManager} (sizes, deleting).
 */
final class AsrInstaller {
    /** The phone has less free room than the download needs. */
    static final class NoSpace extends IOException {
        final long neededBytes;

        NoSpace(long neededBytes) {
            super("Not enough free space: " + neededBytes + " bytes needed");
            this.neededBytes = neededBytes;
        }
    }

    /** How far the install is: a share of everything that has to be downloaded, 0 to 100, and what is loading now. */
    interface Progress {
        void onProgress(int percent, boolean library);
    }

    /** Room kept free beyond the download, so that the phone does not choke on a full disk. */
    private static final long SPARE_BYTES = 100L * 1024 * 1024;

    private AsrInstaller() {
    }

    static File root(Context context) {
        return new File(new File(context.getFilesDir(), "arsound"), "asr");
    }

    static File libraryFile(Context context) {
        return new File(new File(root(context), "lib-" + AsrLibrary.VERSION), AsrLibrary.FILE_NAME);
    }

    static File modelDirectory(Context context, AsrModel model) {
        return new File(new File(root(context), "models"), model.id);
    }

    static boolean isLibraryInstalled(Context context) {
        File file = libraryFile(context);
        return file.isFile() && file.length() == AsrLibrary.FILE_SIZE;
    }

    static boolean isModelInstalled(Context context, AsrModel model) {
        File folder = modelDirectory(context, model);
        for (AsrFile file : model.files()) {
            File local = new File(folder, file.name);
            if (!local.isFile() || local.length() != file.size) return false;
        }
        return true;
    }

    /** What still has to be downloaded to recognise with the model: the library and the files of the model not yet here. */
    static long missingBytes(Context context, AsrModel model) {
        long missing = isLibraryInstalled(context) ? 0 : AsrLibrary.ARCHIVE_SIZE;
        File folder = modelDirectory(context, model);
        for (AsrFile file : model.files()) {
            File local = new File(folder, file.name);
            if (!local.isFile() || local.length() != file.size) missing += file.size;
        }
        return missing;
    }

    /** Space the library and every model take on the phone, in bytes (partial downloads included). */
    static long installedBytes(Context context) {
        return sizeOf(root(context));
    }

    /** Space one model takes, in bytes. */
    static long modelBytes(Context context, AsrModel model) {
        return sizeOf(modelDirectory(context, model));
    }

    private static long sizeOf(File file) {
        if (file.isFile()) return file.length();
        File[] children = file.listFiles();
        if (children == null) return 0;
        long total = 0;
        for (File child : children) total += sizeOf(child);
        return total;
    }

    /**
     * Downloads what is missing for the model. Safe to call again after a failure or a cancel: finished files stay and
     * partial ones go on from where they stopped.
     *
     * @throws NoSpace                      If the phone lacks room.
     * @throws ResumableDownload.Cancelled  If the cancel flag turned true.
     */
    static void install(Context context, AsrModel model, ResumableDownload.Cancel cancel, Progress progress)
            throws IOException {
        File root = root(context);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("No folder " + root);
        boolean needLibrary = !isLibraryInstalled(context);
        long missing = missingBytes(context, model);
        if (missing == 0) return;
        // The archive is kept beside the cut-out library for a moment, so the library counts twice.
        long needed = missing + (needLibrary ? AsrLibrary.FILE_SIZE : 0) + SPARE_BYTES;
        if (root.getUsableSpace() < needed) throw new NoSpace(needed);

        final long total = Math.max(1, missing);
        final long[] finished = {0};
        if (needLibrary) {
            installLibrary(context, cancel, new ResumableDownload.Progress() {
                @Override
                public void onBytes(long done, long all) {
                    progress.onProgress(percent(done, total), true);
                }
            });
            finished[0] += AsrLibrary.ARCHIVE_SIZE;
        }
        File folder = modelDirectory(context, model);
        for (AsrFile file : model.files()) {
            File local = new File(folder, file.name);
            if (local.isFile() && local.length() == file.size) continue;
            final long before = finished[0];
            ResumableDownload.fetch(file.url, local, file.size, file.sha256, cancel, new ResumableDownload.Progress() {
                @Override
                public void onBytes(long done, long all) {
                    progress.onProgress(percent(before + done, total), false);
                }
            });
            finished[0] += file.size;
        }
        progress.onProgress(100, false);
    }

    private static int percent(long done, long total) {
        return (int) Math.max(0, Math.min(100, done * 100 / total));
    }

    /** Downloads the archive, cuts the library out, checks it, and removes the archive and any older version. */
    private static void installLibrary(Context context, ResumableDownload.Cancel cancel,
                                       ResumableDownload.Progress progress) throws IOException {
        File target = libraryFile(context);
        File folder = target.getParentFile();
        File archive = new File(root(context), "sherpa-onnx-" + AsrLibrary.VERSION + ".aar");
        ResumableDownload.fetch(AsrLibrary.ARCHIVE_URL, archive, AsrLibrary.ARCHIVE_SIZE, AsrLibrary.ARCHIVE_SHA256,
                cancel, progress);
        if (folder != null && !folder.isDirectory() && !folder.mkdirs()) throw new IOException("No folder " + folder);
        File partial = new File(target.getPath() + ".part");
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = zip.getEntry(AsrLibrary.ENTRY);
            if (entry == null) throw new IOException("The archive has no " + AsrLibrary.ENTRY);
            try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[256 * 1024];
                int count;
                while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            }
        }
        if (partial.length() != AsrLibrary.FILE_SIZE || !AsrLibrary.FILE_SHA256.equals(ResumableDownload.hashOf(partial))) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw new ResumableDownload.BadChecksum(AsrLibrary.FILE_NAME);
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (!partial.renameTo(target)) throw new IOException("Could not put the library in place");
        // Android 14 wants code that is loaded at run time to be read-only; harmless before.
        //noinspection ResultOfMethodCallIgnored
        target.setReadOnly();
        //noinspection ResultOfMethodCallIgnored
        archive.delete();
        removeOlderLibraries(context, folder);
        Logger.printInfo(() -> "Transcribe: the library " + AsrLibrary.VERSION + " is installed");
    }

    private static void removeOlderLibraries(Context context, File keep) {
        File[] folders = root(context).listFiles();
        if (folders == null) return;
        for (File folder : folders) {
            if (folder.isDirectory() && folder.getName().startsWith("lib-") && !folder.equals(keep)) deleteTree(folder);
        }
    }

    /**
     * Removes a model (and a download of it that was not finished). When no model is left, the library goes too: it
     * is of no use alone.
     */
    static void deleteModel(Context context, AsrModel model) {
        deleteTree(modelDirectory(context, model));
        File[] left = new File(root(context), "models").listFiles();
        if (left == null || left.length == 0) deleteAll(context);
    }

    /** Removes the library, every model and every partial download. */
    static void deleteAll(Context context) {
        deleteTree(root(context));
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(true);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
