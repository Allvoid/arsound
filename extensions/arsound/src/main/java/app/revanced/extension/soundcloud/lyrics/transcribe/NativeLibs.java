package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.content.Context;

import com.k2fsa.sherpa.onnx.LibraryLoader;

import java.io.File;

import app.revanced.extension.shared.Logger;

/**
 * Loads the downloaded native library into the app, once per process. The vendored sherpa-onnx classes do not load
 * anything themselves ({@link LibraryLoader} is switched off), so nothing in them may be used before {@link #load}.
 * <p>
 * Used by {@link WhisperRunner}.
 */
final class NativeLibs {
    private static boolean loaded;

    private NativeLibs() {
    }

    /**
     * @return Whether the library is loaded now. False when it is not installed or the system refused it (a damaged
     * file, an unfit phone); in the second case the file is removed so that the next install starts clean.
     */
    static synchronized boolean load(Context context) {
        if (loaded) return true;
        if (!DeviceState.isSupportedDevice() || !AsrInstaller.isLibraryInstalled(context)) return false;
        File library = AsrInstaller.libraryFile(context);
        try {
            LibraryLoader.setAutoLoadEnabled(false);
            System.load(library.getAbsolutePath());
            loaded = true;
            Logger.printInfo(() -> "Transcribe: the native library is loaded");
        } catch (UnsatisfiedLinkError | SecurityException ex) {
            Logger.printException(() -> "Transcribe: the native library could not be loaded", ex);
            //noinspection ResultOfMethodCallIgnored
            library.setWritable(true);
            //noinspection ResultOfMethodCallIgnored
            library.delete();
        }
        return loaded;
    }
}
