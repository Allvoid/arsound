// Vendored from https://github.com/k2-fsa/sherpa-onnx, tag v1.13.8 (Apache License 2.0, see NOTICE.txt).
// Changed for Arsound: the library is never loaded here. Arsound downloads it (see
// app.revanced.extension.soundcloud.lyrics.transcribe.NativeLibs) and loads it with System.load, so the desktop
// loader (LibraryUtils, which unpacks a library from a jar) is left out and automatic loading starts switched off.

package com.k2fsa.sherpa.onnx;

public class LibraryLoader {
    private static volatile boolean autoLoadEnabled = false;

    static synchronized void loadLibrary() {
        // Nothing to do: Arsound has already loaded the library by the time any class here is used.
    }

    public static void setAutoLoadEnabled(boolean enabled) {
        autoLoadEnabled = enabled;
    }

    static void maybeLoad() {
        if (autoLoadEnabled) {
            loadLibrary();
        }
    }
}
