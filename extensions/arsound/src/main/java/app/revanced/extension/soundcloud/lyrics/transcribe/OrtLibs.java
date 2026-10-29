package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.content.Context;

import java.io.File;
import java.io.IOException;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Loads the downloaded ONNX Runtime into the app, once per process: the runtime first, then its Java glue. The
 * vendored {@code ai.onnxruntime} classes load the glue themselves from the path set here (the one change made to them),
 * so they may be used only after {@link #load}.
 * <p>
 * Used by {@link MdxSeparator}.
 */
final class OrtLibs {
    private static boolean loaded;

    private OrtLibs() {
    }

    static synchronized void load() throws IOException {
        if (loaded) return;
        Context context = Utils.getContext();
        if (context == null || !AsrInstaller.isInstalled(context, NativeArchive.ONNX_RUNTIME)) {
            throw new IOException("ONNX Runtime is not installed");
        }
        try {
            for (NativeArchive.Part part : NativeArchive.ONNX_RUNTIME.parts()) {
                File library = AsrInstaller.libraryFile(context, NativeArchive.ONNX_RUNTIME, part);
                if (part.name.equals("libonnxruntime.so")) {
                    // The glue needs the runtime by its name: it is found among the libraries already loaded.
                    System.load(library.getAbsolutePath());
                } else {
                    // "libonnxruntime4j_jni.so" is the library "onnxruntime4j_jni".
                    String bare = part.name.substring(3, part.name.length() - 3);
                    System.setProperty("onnxruntime.native." + bare + ".path", library.getAbsolutePath());
                }
            }
            ai.onnxruntime.OrtEnvironment.getEnvironment();
            loaded = true;
            Logger.printInfo(() -> "Transcribe: ONNX Runtime is loaded");
        } catch (UnsatisfiedLinkError | RuntimeException ex) {
            Logger.printException(() -> "Transcribe: ONNX Runtime could not be loaded", ex);
            AsrInstaller.removeLibraries(context, NativeArchive.ONNX_RUNTIME);
            throw new IOException("ONNX Runtime could not be loaded: " + ex.getMessage(), ex);
        }
    }
}
