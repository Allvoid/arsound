package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import app.revanced.extension.shared.Logger;

/**
 * The Hexagon neural processor of a Snapdragon, reached through Qualcomm's QNN in ONNX Runtime. The voice separation
 * model runs there in half precision about fifteen times faster than on the processor's cores (Galaxy S25 Ultra: 0.23
 * seconds a segment instead of 3; a 2:09 song is separated in 8 seconds instead of about 3 minutes), and the words
 * recognised afterwards are the same (our five test songs: 0.41 wrong words either way, 29.10).
 * <p>
 * Only the generations the libraries are cut out for are used: V73 (Snapdragon 8 Gen 2), V75 (8 Gen 3) and V79
 * (8 Elite); every other phone runs the model on its cores. The model is compiled for the processor once (about 40
 * seconds) and the result is kept beside it. A phone where the processor does not take the model is remembered and not
 * asked again; so is one where the compiling was cut short twice (the app taken down while it ran). Both hold until the
 * app is updated: a new version may bring what was missing.
 * <p>
 * The app reaches the processor through the vendor's {@code libcdsprpc.so}, which it names in its manifest (the lyrics
 * patch): without that, Android 12 and later keep the library from the app and the processor cannot be set up. That
 * library looks for the processor-side code ({@code libQnnHtpV79Skel.so}) only in the folders of
 * {@code ADSP_LIBRARY_PATH}, so the folder of the downloaded libraries is put first there.
 * <p>
 * The libraries are installed by {@link AsrInstaller} ({@link NativeArchive#QNN_HTP}); used by {@link MdxSeparator}.
 */
final class Npu {
    /** Where the vendor keeps the processor-side libraries: FastRPC's own list, after the folder of ours. */
    private static final String VENDOR_FOLDERS = "/vendor/lib64/rfs/dsp;/vendor/lib/rfsa/adsp;/vendor/lib/rfsa/dsp;/vendor/dsp";

    /** The Hexagon generation of each Snapdragon ({@link Build#SOC_MODEL}) it is used on. */
    private static final String[][] GENERATIONS = {{"SM8550", "73"}, {"SM8650", "75"}, {"SM8750", "79"}};

    /** Compilings cut short after which the processor is not asked again. */
    private static final int ATTEMPTS = 2;

    private Npu() {
    }

    /** The Hexagon generation of this phone ("79"), or null where the model stays on the cores. */
    static String generation() {
        if (Build.VERSION.SDK_INT < 31 || !"QTI".equalsIgnoreCase(Build.SOC_MANUFACTURER)) return null;
        for (String[] known : GENERATIONS) {
            if (known[0].equalsIgnoreCase(Build.SOC_MODEL)) return known[1];
        }
        return null;
    }

    /**
     * Whether the libraries for the processor are wanted: a known Snapdragon where it has not failed since the app was
     * installed or updated.
     */
    static boolean wanted(Context context) {
        return generation() != null && !isRefused(context);
    }

    private static boolean isRefused(Context context) {
        File refused = refusedFile(context);
        if (!refused.isFile()) return false;
        if (readNumbers(refused)[0] == installed(context)) return true;
        //noinspection ResultOfMethodCallIgnored
        refused.delete();
        return false;
    }

    /**
     * The model in a session on the processor, or null where there is none or it does not take the model (then the
     * cores do the work). Compiles the model the first time.
     */
    static OrtSession open(Context context, OrtEnvironment environment, File model) {
        if (!wanted(context) || !AsrInstaller.isInstalled(context, NativeArchive.QNN_HTP)) return null;
        File compiled = compiledFile(model);
        if (compiled.isFile()) {
            try (OrtSession.SessionOptions options = options(context, null)) {
                OrtSession session = environment.createSession(compiled.getAbsolutePath(), options);
                Logger.printInfo(() -> "Transcribe: the voice is separated on the neural processor");
                return session;
            } catch (OrtException | RuntimeException ex) {
                Logger.printInfo(() -> "Transcribe: the compiled model did not load, compiling it again", ex);
                //noinspection ResultOfMethodCallIgnored
                compiled.delete();
            }
        }
        File attempts = attemptsFile(context);
        long installed = installed(context);
        long[] before = readNumbers(attempts);
        long cutShort = before[0] == installed ? before[1] : 0;
        if (cutShort >= ATTEMPTS) {
            refuse(context, "the compiling was cut short " + cutShort + " times");
            return null;
        }
        write(attempts, installed + " " + (cutShort + 1));
        File partial = new File(compiled.getPath() + ".part");
        //noinspection ResultOfMethodCallIgnored
        partial.delete();
        long start = System.nanoTime();
        try (OrtSession.SessionOptions options = options(context, partial)) {
            OrtSession session = environment.createSession(model.getAbsolutePath(), options);
            //noinspection ResultOfMethodCallIgnored
            attempts.delete();
            // Without the file in place the model is compiled again next time, nothing worse.
            if (!partial.renameTo(compiled)) {
                //noinspection ResultOfMethodCallIgnored
                partial.delete();
            }
            long seconds = (System.nanoTime() - start) / 1_000_000_000L;
            Logger.printInfo(() -> "Transcribe: the model is compiled for the neural processor in " + seconds + " s");
            return session;
        } catch (OrtException | RuntimeException | UnsatisfiedLinkError ex) {
            //noinspection ResultOfMethodCallIgnored
            attempts.delete();
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            // Expected on some phones, so no error message for the user: the cores take over.
            Logger.printInfo(() -> "Transcribe: the neural processor does not take the model: " + ex);
            refuse(context, ex.toString());
            return null;
        }
    }

    /**
     * Session options for the processor; with {@code compileTo}, the compiled model is written there. Every node of
     * the model must go to the processor: a model split between it and the cores would run on one core.
     */
    private static OrtSession.SessionOptions options(Context context, File compileTo) throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        try {
            options.setIntraOpNumThreads(1);
            options.setCPUArenaAllocator(false);
            options.setMemoryPatternOptimization(false);
            // The model leaves its batch size open; the processor needs every size fixed.
            options.setSymbolicDimensionValue("batch_size", 1);
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
            if (compileTo != null) {
                options.addConfigEntry("ep.context_enable", "1");
                options.addConfigEntry("ep.context_embed_mode", "1");
                options.addConfigEntry("ep.context_file_path", compileTo.getAbsolutePath());
            }
            File backend = backend(context);
            try {
                Os.setenv("ADSP_LIBRARY_PATH", backend.getParent() + ";" + VENDOR_FOLDERS, true);
            } catch (ErrnoException ex) {
                Logger.printInfo(() -> "Transcribe: could not point the processor at its libraries", ex);
            }
            Map<String, String> qnn = new HashMap<>();
            qnn.put("backend_path", backend.getAbsolutePath());
            qnn.put("htp_performance_mode", "burst");
            qnn.put("enable_htp_fp16_precision", "1");
            options.addQnn(qnn);
            return options;
        } catch (OrtException | RuntimeException ex) {
            options.close();
            throw ex;
        }
    }

    private static File backend(Context context) {
        for (NativeArchive.Part part : NativeArchive.QNN_HTP.parts()) {
            if (part.name.equals("libQnnHtp.so")) return AsrInstaller.libraryFile(context, NativeArchive.QNN_HTP, part);
        }
        throw new IllegalStateException("No QNN backend in the archive");
    }

    /** The compiled model, beside the model: it holds for this generation and this version of the libraries. */
    private static File compiledFile(File model) {
        String name = model.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return new File(model.getParentFile(), name + ".v" + generation() + ".qnn-" + NativeArchive.QNN_HTP.version
                + ".onnx");
    }

    /**
     * Marks the processor as not to be asked again until the app is updated (for this version of the libraries) and
     * frees their room.
     */
    private static void refuse(Context context, String why) {
        Logger.printInfo(() -> "Transcribe: the neural processor is not used until the app is updated: " + why);
        write(refusedFile(context), installed(context) + " " + why.replace('\n', ' '));
        AsrInstaller.removeLibraries(context, NativeArchive.QNN_HTP);
    }

    /** When the app was installed or last updated. */
    private static long installed(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException ex) {
            return 0;
        }
    }

    private static File refusedFile(Context context) {
        return new File(AsrInstaller.root(context), "npu-refused-" + NativeArchive.QNN_HTP.version);
    }

    private static File attemptsFile(Context context) {
        return new File(AsrInstaller.root(context), "npu-compiling-" + NativeArchive.QNN_HTP.version);
    }

    /** The first two numbers in the file (what does not parse is 0; no file is {0, 0}). */
    private static long[] readNumbers(File file) {
        long[] numbers = new long[2];
        try {
            if (!file.isFile()) return numbers;
            String[] words = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim().split(" ");
            for (int i = 0; i < Math.min(2, words.length); i++) numbers[i] = Long.parseLong(words[i]);
        } catch (IOException | NumberFormatException ex) {
            // Read as far as it went.
        }
        return numbers;
    }

    private static void write(File file, String text) {
        try {
            Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            Logger.printInfo(() -> "Transcribe: could not write " + file, ex);
        }
    }
}
