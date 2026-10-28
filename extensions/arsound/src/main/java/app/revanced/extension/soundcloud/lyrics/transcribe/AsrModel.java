package app.revanced.extension.soundcloud.lyrics.transcribe;

import app.revanced.extension.soundcloud.shared.Lang;

/**
 * A speech model that can recognise the words of a song: OpenAI Whisper, converted to ONNX and shrunk to 8 bits by
 * the sherpa-onnx authors. The files are downloaded once from Hugging Face, from a fixed commit of the repository, and
 * each is checked against the SHA-256 written here.
 * <p>
 * Used by {@link AsrInstaller} (what to download), {@link WhisperRunner} (what to load) and the settings rows. To move
 * to other files, see "Распознавание текста нейросетью" in BUILDING.md.
 */
public enum AsrModel {
    /**
     * The default: in our test on Russian songs about a third of the words came out exactly right and some 60 percent
     * of the letters; a third of a gigabyte to download and about a gigabyte of memory at work.
     */
    SMALL("small", "Точная (small)", "Accurate (small)", 2, "csukuangfj/sherpa-onnx-whisper-small",
            "8f3c18b358db4d1f2fc1eae49d75cd20989e4309", 1500L * 1024 * 1024,
            new String[][]{
                    {"small-encoder.int8.onnx", "112442483", "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9"},
                    {"small-decoder.int8.onnx", "262226114", "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee"},
                    {"small-tokens.txt", "816730", "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"}}),
    /**
     * The fast one: half the memory and twice as fast, but on Russian songs it makes more mistakes than there are
     * words (in our test), so it is only for English speech and for phones too weak for the accurate one.
     */
    BASE("base", "Быстрая (base), много ошибок", "Fast (base), many mistakes", 1, "csukuangfj/sherpa-onnx-whisper-base",
            "bb53ee204431c90d314c1cc08d28d23e5b7927cc", 800L * 1024 * 1024,
            new String[][]{
                    {"base-encoder.int8.onnx", "29120534", "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"},
                    {"base-decoder.int8.onnx", "130672026", "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"},
                    {"base-tokens.txt", "816730", "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"}});

    /** The word this model is saved under in the settings and in the names of stored texts. */
    public final String id;
    /** Memory that must be free to load it (the phone is asked before work starts). */
    final long minAvailableBytes;
    private final String russian;
    private final String english;
    private final int quality;
    private final AsrFile[] files;

    AsrModel(String id, String russian, String english, int quality, String repository, String commit,
             long minAvailableBytes, String[][] table) {
        this.id = id;
        this.russian = russian;
        this.english = english;
        this.quality = quality;
        this.minAvailableBytes = minAvailableBytes;
        this.files = new AsrFile[table.length];
        for (int i = 0; i < table.length; i++) {
            String name = table[i][0];
            files[i] = new AsrFile(name, "https://huggingface.co/" + repository + "/resolve/" + commit + "/" + name,
                    Long.parseLong(table[i][1]), table[i][2]);
        }
    }

    /** The name for the user, in the language of the app. */
    public String label() {
        return Lang.text(russian, english);
    }

    /** How well it hears compared with the others: the higher the better. */
    int quality() {
        return quality;
    }

    /** The model saved under the word, or null for a word nobody writes. */
    public static AsrModel fromId(String id) {
        for (AsrModel model : values()) {
            if (model.id.equals(id)) return model;
        }
        return null;
    }

    /** The model the settings name; the default one if they name none. */
    public static AsrModel fromSetting(String id) {
        AsrModel model = fromId(id);
        return model == null ? SMALL : model;
    }

    /** The two ONNX files and the token table, in download order. */
    AsrFile[] files() {
        return files.clone();
    }

    /** What the downloads add up to, in bytes. */
    public long totalBytes() {
        long total = 0;
        for (AsrFile file : files) total += file.size;
        return total;
    }

    /** The file of the encoder (the first ONNX file) in the folder the model is installed in. */
    String encoderName() {
        return files[0].name;
    }

    String decoderName() {
        return files[1].name;
    }

    String tokensName() {
        return files[2].name;
    }
}
