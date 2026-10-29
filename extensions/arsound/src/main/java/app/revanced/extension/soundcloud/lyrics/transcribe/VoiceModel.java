package app.revanced.extension.soundcloud.lyrics.transcribe;

/**
 * The model that takes the voice out of the music before the speech model listens: UVR-MDX-NET Voc_FT of the
 * Ultimate Vocal Remover project, in the ONNX form the sherpa-onnx authors publish (with its sizes in the metadata).
 * One file, checked against the SHA-256 written here.
 * <p>
 * In our test on Russian songs (29.10) the voice alone took the share of wrong words of the Russian model from 0.63 to
 * 0.41 and of Whisper small from 0.77 to 0.67. It is slow: on a computer about half the length of the song, on a phone
 * several times more. Installed by {@link AsrInstaller}, run by {@link MdxSeparator}.
 */
final class VoiceModel {
    static final AsrFile FILE = new AsrFile("UVR-MDX-NET-Voc_FT.onnx",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/UVR-MDX-NET-Voc_FT.onnx",
            66_762_795L, "e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4");

    /** Memory that must be free to load it, besides what the speech model needs afterwards. */
    static final long MIN_AVAILABLE_BYTES = 600L * 1024 * 1024;

    private VoiceModel() {
    }
}
