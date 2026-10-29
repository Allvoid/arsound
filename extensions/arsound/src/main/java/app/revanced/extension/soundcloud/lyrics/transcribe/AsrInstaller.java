package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import app.revanced.extension.shared.Logger;

/**
 * Puts the native libraries, the speech model and the voice separation model on the phone, and takes them off.
 * Everything lives in {@code files/arsound/asr}: each library archive's files in a folder named after its version (a
 * new version is a new folder, the old one is removed), each speech model in a folder of its own, the separation model
 * in {@code voice}. Nothing is ever kept as a half-file under its final name, so
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

    /** The sherpa-onnx library (one file per processor). */
    static File libraryFile(Context context) {
        return libraryFile(context, NativeArchive.SHERPA, NativeArchive.SHERPA.parts().get(0));
    }

    static File libraryFile(Context context, NativeArchive archive, NativeArchive.Part part) {
        return new File(new File(root(context), archive.folderName()), part.name);
    }

    static File voiceModelFile(Context context) {
        return new File(new File(root(context), "voice"), VoiceModel.FILE.name);
    }

    static File modelDirectory(Context context, AsrModel model) {
        return new File(new File(root(context), "models"), model.id);
    }

    static boolean isLibraryInstalled(Context context) {
        return isInstalled(context, NativeArchive.SHERPA);
    }

    /** Every library of the archive for this phone is cut out and whole. */
    static boolean isInstalled(Context context, NativeArchive archive) {
        if (archive.parts().isEmpty()) return false;
        for (NativeArchive.Part part : archive.parts()) {
            File file = libraryFile(context, archive, part);
            if (!file.isFile() || file.length() != part.size) return false;
        }
        return true;
    }

    static boolean isVoiceModelInstalled(Context context) {
        File file = voiceModelFile(context);
        return file.isFile() && file.length() == VoiceModel.FILE.size;
    }

    static boolean isModelInstalled(Context context, AsrModel model) {
        File folder = modelDirectory(context, model);
        for (AsrFile file : model.files()) {
            File local = new File(folder, file.name);
            if (!local.isFile() || local.length() != file.size) return false;
        }
        return true;
    }

    /**
     * What still has to be downloaded to recognise with the model: the libraries and the files of the model not yet
     * here, and with the voice taken out of the music, ONNX Runtime (with the neural processor's libraries on a
     * Snapdragon that has one, {@link Npu}) and the separation model.
     */
    static long missingBytes(Context context, AsrModel model, boolean separate) {
        long missing = isLibraryInstalled(context) ? 0 : NativeArchive.SHERPA.size;
        if (separate) {
            for (NativeArchive archive : separationArchives(context)) {
                if (!isInstalled(context, archive)) missing += archive.size;
            }
            if (!isVoiceModelInstalled(context)) missing += VoiceModel.FILE.size;
        }
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
    static void install(Context context, AsrModel model, boolean separate, ResumableDownload.Cancel cancel,
                        Progress progress) throws IOException {
        File root = root(context);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("No folder " + root);
        List<NativeArchive> archives = new ArrayList<>();
        if (!isLibraryInstalled(context)) archives.add(NativeArchive.SHERPA);
        if (separate) {
            for (NativeArchive archive : separationArchives(context)) {
                if (!isInstalled(context, archive)) archives.add(archive);
            }
        }
        long missing = missingBytes(context, model, separate);
        if (missing == 0) return;
        // An archive is kept beside the cut-out libraries for a moment, so the libraries count twice.
        long unpacked = 0;
        for (NativeArchive archive : archives) unpacked += archive.unpackedBytes();
        long needed = missing + unpacked + SPARE_BYTES;
        if (root.getUsableSpace() < needed) throw new NoSpace(needed);

        final long total = Math.max(1, missing);
        final long[] finished = {0};
        for (NativeArchive archive : archives) {
            final long before = finished[0];
            installLibrary(context, archive, cancel, new ResumableDownload.Progress() {
                @Override
                public void onBytes(long done, long all) {
                    progress.onProgress(percent(before + done, total), true);
                }
            });
            finished[0] += archive.size;
        }
        if (separate && !isVoiceModelInstalled(context)) {
            final long before = finished[0];
            ResumableDownload.fetch(VoiceModel.FILE.url, voiceModelFile(context), VoiceModel.FILE.size,
                    VoiceModel.FILE.sha256, cancel, new ResumableDownload.Progress() {
                        @Override
                        public void onBytes(long done, long all) {
                            progress.onProgress(percent(before + done, total), false);
                        }
                    });
            finished[0] += VoiceModel.FILE.size;
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

    /** The libraries the voice separation runs on, on this phone. */
    private static List<NativeArchive> separationArchives(Context context) {
        List<NativeArchive> archives = new ArrayList<>();
        archives.add(NativeArchive.ONNX_RUNTIME);
        if (Npu.wanted(context)) archives.add(NativeArchive.QNN_HTP);
        return archives;
    }

    private static int percent(long done, long total) {
        return (int) Math.max(0, Math.min(100, done * 100 / total));
    }

    /** Downloads the archive, cuts the libraries out, checks them, and removes the archive and any older version. */
    private static void installLibrary(Context context, NativeArchive library, ResumableDownload.Cancel cancel,
                                       ResumableDownload.Progress progress) throws IOException {
        File folder = new File(root(context), library.folderName());
        File archive = new File(root(context), library.archiveName());
        ResumableDownload.fetch(library.url, archive, library.size, library.sha256, cancel, progress);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("No folder " + folder);
        try (ZipFile zip = new ZipFile(archive)) {
            for (NativeArchive.Part part : library.parts()) {
                File target = libraryFile(context, library, part);
                File partial = new File(target.getPath() + ".part");
                ZipEntry entry = zip.getEntry(part.entry);
                if (entry == null) throw new IOException("The archive has no " + part.entry);
                try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[256 * 1024];
                    int count;
                    while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
                }
                if (partial.length() != part.size || !part.sha256.equals(ResumableDownload.hashOf(partial))) {
                    //noinspection ResultOfMethodCallIgnored
                    partial.delete();
                    throw new ResumableDownload.BadChecksum(part.name);
                }
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                if (!partial.renameTo(target)) throw new IOException("Could not put " + part.name + " in place");
                // Android 14 wants code that is loaded at run time to be read-only; harmless before.
                //noinspection ResultOfMethodCallIgnored
                target.setReadOnly();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        archive.delete();
        removeOlderLibraries(context, library, folder);
        Logger.printInfo(() -> "Transcribe: " + library.folderName() + " is installed");
    }

    private static void removeOlderLibraries(Context context, NativeArchive library, File keep) {
        File[] folders = root(context).listFiles();
        if (folders == null) return;
        for (File folder : folders) {
            if (folder.isDirectory() && folder.getName().startsWith(library.prefix) && !folder.equals(keep)) {
                deleteTree(folder);
            }
        }
    }

    /** Removes the libraries of an archive that the system refused to load, so the next install starts clean. */
    static void removeLibraries(Context context, NativeArchive library) {
        deleteTree(new File(root(context), library.folderName()));
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
