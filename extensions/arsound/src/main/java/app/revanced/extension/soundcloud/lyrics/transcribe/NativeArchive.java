package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * Native libraries that are not in the app: they are cut out of an official release archive, downloaded on demand.
 * The archive and every library in it are checked against the SHA-256 written here, never against anything the server
 * says. Only the libraries for the phone's processor are cut out (64-bit ARM; x86-64 for emulators and Chromebooks),
 * and of Qualcomm's neural processor libraries only those of the phone's generation ({@link Npu}).
 * <p>
 * Everything is pinned. The Java classes that talk to a library are vendored from the same tag ({@code com.k2fsa.sherpa
 * .onnx}, {@code ai.onnxruntime}): the native code and those classes must agree field by field. To move to a newer
 * release, see "Распознавание текста нейросетью" in BUILDING.md. Installed by {@link AsrInstaller}, loaded by
 * {@link NativeLibs}, {@link OrtLibs} and {@link Npu}.
 */
final class NativeArchive {
    /** One library inside the archive. */
    static final class Part {
        final String abi;
        final String entry;
        final String name;
        final long size;
        final String sha256;
        /** The Hexagon generation the library is for ({@link Npu#generation}), or null for every phone. */
        final String generation;

        Part(String abi, String name, long size, String sha256) {
            this(abi, name, size, sha256, null);
        }

        Part(String abi, String name, long size, String sha256, String generation) {
            this.abi = abi;
            this.entry = "jni/" + abi + "/" + name;
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
            this.generation = generation;
        }
    }

    /** The processors the libraries are cut out for. Above the archives: {@link #ONNX_RUNTIME} is chosen with it. */
    static final String[] ABIS = {"arm64-v8a", "x86_64"};

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
     * ONNX Runtime with its Java interface for 64-bit ARM: the voice separation model, which sherpa-onnx cannot run
     * from Java. The official AAR of the build with Qualcomm's QNN from Maven Central: it runs the model on the cores
     * like the plain build and can also hand it to a Snapdragon's neural processor ({@link #QNN_HTP}). The runtime is
     * loaded before its Java glue.
     */
    static final NativeArchive ONNX_RUNTIME_QNN = new NativeArchive("ort-", "1.22.0-qnn",
            "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/1.22.0/onnxruntime-android-qnn-1.22.0.aar",
            6_796_460L, "8be5cc2e5a56bf44673582661ca0e3731c12a9d57fae789c92f367e67755275f",
            new Part("arm64-v8a", "libonnxruntime.so", 18_120_344L,
                    "6eb92945968524b370299ccf39089a02c12ff077d96ecdf9aa875c90735b545f"),
            new Part("arm64-v8a", "libonnxruntime4j_jni.so", 100_456L,
                    "9111b09bdb8c00006e46f38df69883d6c186ed2a8c355449cd18dd56bb2cc78d"));

    /** The same for x86-64, which the QNN build has no library for: the plain Android AAR. */
    static final NativeArchive ONNX_RUNTIME_PLAIN = new NativeArchive("ort-", "1.22.0",
            "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.22.0/onnxruntime-android-1.22.0.aar",
            28_515_295L, "04a4617a9c797cf49225595e45b5546081cb34c86ac817581141577d3b7dbfe2",
            new Part("x86_64", "libonnxruntime.so", 21_880_016L,
                    "e3e67500ac56271802355bad3a46dcfcb90ce6392d9c4793b5a2c48da0d2a4e9"),
            new Part("x86_64", "libonnxruntime4j_jni.so", 90_728L,
                    "bc96f7f9245dbf66110dccab9394c9283188ce28c4ad6d8ba3dc1ded842b9324"));

    /** ONNX Runtime for this phone's processor. */
    static final NativeArchive ONNX_RUNTIME = "x86_64".equals(abi()) ? ONNX_RUNTIME_PLAIN : ONNX_RUNTIME_QNN;

    /**
     * Qualcomm's QNN libraries for the Hexagon neural processor ({@link Npu}), the version ONNX Runtime 1.22.0 is built
     * against, from Maven Central: the backend, the compiler that turns the model into the processor's code (needed
     * only the first time, kept for a compiled model that stops loading), the system library, and the processor-side
     * code of each generation (only the phone's own is cut out). Downloaded only for a phone that
     * {@link Npu#wanted wants} it.
     */
    static final NativeArchive QNN_HTP = new NativeArchive("qnn-", "2.33.0",
            "https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/2.33.0/qnn-runtime-2.33.0.aar",
            42_416_342L, "d41cd9bad577734c1ee165ec5977ef239e4aaf72dda25cacb137e70d37737bfc",
            new Part("arm64-v8a", "libQnnHtp.so", 2_120_168L,
                    "eed70adfc8b16c8ab8756569c57ea5109cad0140ee301b9472a2e80fcfe62f42"),
            new Part("arm64-v8a", "libQnnHtpPrepare.so", 51_782_664L,
                    "ecd6700bffa6eeb944155b6961674fa23cbdbcaec65ffe48d39f77cc3d77870a"),
            new Part("arm64-v8a", "libQnnSystem.so", 1_750_096L,
                    "f3542bb603c9f2b2d02f25c34bbc5efc2611f96bb353443e7eaeb40ec63b51f6"),
            new Part("arm64-v8a", "libQnnHtpV73Stub.so", 471_880L,
                    "5d772d5355659f829ad6b08240223dba44744cccee6b7c2407a9f27bcc5be736", "73"),
            new Part("arm64-v8a", "libQnnHtpV73Skel.so", 8_768_236L,
                    "2ddefaeda89d86e48b31b599c005758c72519470f8f48dbe2cb52eb13886b637", "73"),
            new Part("arm64-v8a", "libQnnHtpV75Stub.so", 471_880L,
                    "7f5fe8ff619ce42ee02fda85544414736d4f864505abdf09d029e4c47a771eab", "75"),
            new Part("arm64-v8a", "libQnnHtpV75Skel.so", 13_556_776L,
                    "452ec685a976e8747cb36d6447e6224e977e49aa1262b886115850c30788690a", "75"),
            new Part("arm64-v8a", "libQnnHtpV79Stub.so", 471_880L,
                    "4f5b2efa7a28ac0b73d69999c65e076c1aa4afeae0928b2d142b6370a240c25a", "79"),
            new Part("arm64-v8a", "libQnnHtpV79Skel.so", 13_459_244L,
                    "798fd74659659e5b8dd1d8aa370f138e72df5afcca5e59fcc3ea41e25f5c8257", "79"));

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
        String[] supported = Build.SUPPORTED_ABIS;
        // Unit tests on a computer have no values here.
        if (supported == null) return null;
        for (String abi : supported) {
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
        String generation = Npu.generation();
        for (Part part : parts) {
            if (part.abi.equals(abi) && (part.generation == null || part.generation.equals(generation))) mine.add(part);
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
