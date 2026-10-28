package app.revanced.extension.soundcloud.lyrics.transcribe;

/**
 * The native part of the recognition: the one library that holds sherpa-onnx and ONNX Runtime together, for 64-bit
 * ARM phones. It is not in the app. It is taken from the official sherpa-onnx release on GitHub (the AAR that links
 * ONNX Runtime in), checked against the SHA-256 written here, and only the one file we need is cut out of it.
 * <p>
 * Everything here is pinned: the Java sources in {@code com/k2fsa/sherpa/onnx} are of the same tag, because the
 * native code and those classes must agree field by field. To move to a newer release, see "Распознавание текста
 * нейросетью" in BUILDING.md. Used by {@link AsrInstaller} and {@link NativeLibs}.
 */
final class AsrLibrary {
    static final String VERSION = "1.13.8";
    static final String ARCHIVE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v" + VERSION
            + "/sherpa-onnx-static-link-onnxruntime-" + VERSION + ".aar";
    static final long ARCHIVE_SIZE = 38_691_998L;
    static final String ARCHIVE_SHA256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471";
    /** The file inside the archive, and what it must be once cut out. */
    static final String ENTRY = "jni/arm64-v8a/libsherpa-onnx-jni.so";
    static final String FILE_NAME = "libsherpa-onnx-jni.so";
    static final long FILE_SIZE = 24_169_352L;
    static final String FILE_SHA256 = "b4a53d915c19cb6d09b1cdc9a8084be54419b647aab9097ffdfb46f30c3c4dd8";

    private AsrLibrary() {
    }
}
