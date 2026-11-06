package app.revanced.extension.soundcloud.local;

import java.util.Locale;

/**
 * The small mark of an imported track: its format and what it was recorded in ("FLAC", "FLAC 24/96", "MP3 320",
 * "AAC 256"). A lossless file of the usual CD quality (16 bit, 44.1 or 48 kHz) is just its format; a lossy file shows
 * its bit rate. The same rules as the mark of the Windows app.
 */
final class QualityLabel {
    /** An MP3 of constant rate reads 319 or 321 from some encoders: the usual step is shown when it is that close. */
    private static final int[] BIT_RATE_STEPS = {64, 96, 128, 160, 192, 224, 256, 320};
    private static final int STEP_TOLERANCE = 2;

    private QualityLabel() {
    }

    /**
     * @param fileName      The name of the file, for its extension.
     * @param codec         The mime type of the audio in it, or null.
     * @param sampleRate    In Hz, 0 when not known.
     * @param bitsPerSample 0 when not known.
     * @param bitRate       In bits a second, 0 when not known.
     * @return The mark, empty when nothing is known about the file.
     */
    static String of(String fileName, String codec, int sampleRate, int bitsPerSample, int bitRate) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toUpperCase(Locale.ROOT);
        if (extension.isEmpty()) return "";
        boolean lossless = isLossless(codec, extension);
        String format = format(extension, lossless);
        if (lossless) {
            boolean plain = bitsPerSample <= 16 && (sampleRate == 44_100 || sampleRate == 48_000 || sampleRate == 0);
            return plain || bitsPerSample <= 0 ? format : format + " " + bitsPerSample + "/" + kilohertz(sampleRate);
        }
        return bitRate <= 0 ? format : format + " " + rounded(bitRate / 1000);
    }

    private static boolean isLossless(String codec, String extension) {
        if (codec != null && codec.toLowerCase(Locale.ROOT).matches("audio/(flac|alac|raw|x-wav|wav|x-aiff|aiff)")) return true;
        return extension.equals("FLAC") || extension.equals("WAV") || extension.equals("AIFF") || extension.equals("AIF");
    }

    private static String format(String extension, boolean lossless) {
        switch (extension) {
            case "M4A":
            case "MP4":
                return lossless ? "ALAC" : "AAC";
            case "OGA":
                return "OGG";
            default:
                return extension;
        }
    }

    private static String kilohertz(int sampleRate) {
        if (sampleRate % 1000 == 0) return String.valueOf(sampleRate / 1000);
        return String.format(Locale.ROOT, "%.1f", sampleRate / 1000.0);
    }

    private static int rounded(int kilobits) {
        for (int step : BIT_RATE_STEPS) {
            if (Math.abs(kilobits - step) <= STEP_TOLERANCE) return step;
        }
        return kilobits;
    }
}
