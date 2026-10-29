package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * Native libraries that are not in the app: they are cut out of an official release archive, downloaded on demand.
 * The archive and every library in it are checked against the SHA-256 written here, never against anything the server
 * says. Only the libraries for the phone's processor are cut out (64-bit ARM; x86-64 for emulators and Chromebooks).
 * <p>
 * Everything is pinned. The Java classes that talk to a library are vendored from the same tag ({@code com.k2fsa.sherpa
 * .onnx}, {@code ai.onnxruntime}): the native code and those classes must agree field by field. To move to a newer
 * release, see "Распознавание текста нейросетью" in BUILDING.md. Installed by {@link AsrInstaller}, loaded by
 * {@link NativeLibs} and {@link OrtLibs}.
 */
final class NativeArchive {
    /** One library inside the archive. */
    static final class Part {
        final String abi;
        final String entry;
        final String name;
        final long size;
        final String sha256;

        Part(String abi, String name, long size, String sha256) {
            this.abi = abi;
            this.entry = "jni/" + abi + "/" + name;
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /**
     * sherpa-onnx with ONNX Runtime linked in: the speech models. The AAR of the release, one library per processor.
     */
    static final NativeArchive SHERPA = new NativeArchive("lib-", "1.13.8",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar",
            38_691_998L, "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471",
            new Part("arm64-v8a", "libsherpa-onnx-jni.so", 24_169_352L,
                    "b4a53d915c19cb6d09b1cdc9a8084be54419b647aab9097ffdfb46f30c3c4dd8"),
            new Part("x86_64", "libsherpa-onnx-jni.so", 27_359_872L,
                    "968e24f61d1e4dae0d5e7860aded42fee45b0f804e1c5a42dfa49186864cefc4"));

    /**
     * ONNX Runtime with its Java interface: the voice separation model, which sherpa-onnx cannot run from Java. The
     * official Android AAR from Maven Central; the runtime is loaded before its Java glue.
     */
    static final NativeArchive ONNX_RUNTIME = new NativeArchive("ort-", "1.22.0",
            "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.22.0/onnxruntime-android-1.22.0.aar",
            28_515_295L, "04a4617a9c797cf49225595e45b5546081cb34c86ac817581141577d3b7dbfe2",
            new Part("arm64-v8a", "libonnxruntime.so", 18_214_224L,
                    "999ecfdb5b5a13e4097487773b6d71ce8a075408a237daab072e8f5e817bd78e"),
            new Part("arm64-v8a", "libonnxruntime4j_jni.so", 100_600L,
                    "5614bd018cca9031c4dbcfc2a3bece42fae52861a92ff09c4bd2491bd93ac758"),
            new Part("x86_64", "libonnxruntime.so", 21_880_016L,
                    "e3e67500ac56271802355bad3a46dcfcb90ce6392d9c4793b5a2c48da0d2a4e9"),
            new Part("x86_64", "libonnxruntime4j_jni.so", 90_728L,
                    "bc96f7f9245dbf66110dccab9394c9283188ce28c4ad6d8ba3dc1ded842b9324"));

    /** The processors the libraries are cut out for. */
    static final String[] ABIS = {"arm64-v8a", "x86_64"};

    /** The folder of an installed version is the prefix and the version; older folders with the prefix are removed. */
    final String prefix;
    final String version;
    final String url;
    final long size;
    final String sha256;
    private final Part[] parts;

    private NativeArchive(String prefix, String version, String url, long size, String sha256, Part... parts) {
        this.prefix = prefix;
        this.version = version;
        this.url = url;
        this.size = size;
        this.sha256 = sha256;
        this.parts = parts;
    }

    /** The processor of this phone that the libraries exist for, or null. */
    static String abi() {
        for (String abi : Build.SUPPORTED_ABIS) {
            for (String known : ABIS) {
                if (known.equals(abi)) return abi;
            }
        }
        return null;
    }

    /** The libraries for this phone, in load order. */
    List<Part> parts() {
        List<Part> mine = new ArrayList<>();
        String abi = abi();
        for (Part part : parts) {
            if (part.abi.equals(abi)) mine.add(part);
        }
        return mine;
    }

    /** What the libraries take once cut out. */
    long unpackedBytes() {
        long total = 0;
        for (Part part : parts()) total += part.size;
        return total;
    }

    String folderName() {
        return prefix + version;
    }

    String archiveName() {
        return prefix + version + ".aar";
    }
}
