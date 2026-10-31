package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.io.IOException;

/**
 * Only the voice of the song for the speech model: the stereo sound is brought to the separation model's rate, its
 * voice taken out piece by piece ({@link VocalsStream}, {@link MdxSeparator}) and resampled to 16 kHz.
 */
final class VocalsTarget implements PcmTarget {
    /** Hears how far the separation is. */
    interface Progress {
        /** Before each piece: may rest (a hot phone) or stop the work. */
        void beforePiece() throws IOException;

        /** The voice is out up to this many milliseconds of the song. */
        void separatedMs(long ms);
    }

    /** The separation model failed on a piece: the model's fault, not the sound's or the network's. */
    static final class SeparationFailed extends IOException {
        SeparationFailed(IOException cause) {
            super("The voice could not be separated: " + cause.getMessage(), cause);
        }
    }

    /** The length of a piece: 15 seconds, as sherpa-onnx does it. */
    private static final int CHUNK = 15 * MdxSeparator.RATE;

    private final GrowableFloats output;
    private final StreamResampler toSpeech = new StreamResampler(MdxSeparator.RATE, Chunker.RATE);
    private final VocalsStream stream;
    private int channels;
    private StreamResampler leftRate;
    private StreamResampler rightRate;
    private final GrowableFloats left = new GrowableFloats(1 << 15);
    private final GrowableFloats right = new GrowableFloats(1 << 15);
    private float[] leftIn = new float[0];
    private float[] rightIn = new float[0];

    /** @param expected The samples the voice of the song is expected to take at 16 kHz, made room for at once. */
    VocalsTarget(MdxSeparator separator, Progress progress, int expected) {
        output = new GrowableFloats(expected);
        stream = new VocalsStream(CHUNK, separator.margin(), (pieceLeft, pieceRight, first, last) -> {
            progress.beforePiece();
            try {
                return separator.separate(pieceLeft, pieceRight, first, last);
            } catch (ResumableDownload.Cancelled ex) {
                throw ex;
            } catch (IOException ex) {
                throw new SeparationFailed(ex);
            }
        }, (mono, count) -> {
            toSpeech.push(mono, count, output);
            progress.separatedMs(output.size() * 1000L / Chunker.RATE);
        });
    }

    @Override
    public void start(int rate, int channels) {
        this.channels = channels;
        // Most songs come at 44.1 kHz already; others (48 kHz) are brought to it, each channel apart.
        if (rate != MdxSeparator.RATE) {
            leftRate = new StreamResampler(rate, MdxSeparator.RATE);
            rightRate = new StreamResampler(rate, MdxSeparator.RATE);
        }
    }

    @Override
    public void push(float[] interleaved, int frames) throws IOException {
        if (leftIn.length < frames) {
            leftIn = new float[frames];
            rightIn = new float[frames];
        }
        for (int i = 0; i < frames; i++) {
            leftIn[i] = interleaved[i * channels];
            // A mono song is its own second channel.
            rightIn[i] = channels > 1 ? interleaved[i * channels + 1] : leftIn[i];
        }
        if (leftRate == null) {
            stream.push(leftIn, rightIn, frames);
            return;
        }
        leftRate.push(leftIn, frames, left);
        rightRate.push(rightIn, frames, right);
        flushResampled();
    }

    /** Hands over what both channels have at the model's rate. */
    private void flushResampled() throws IOException {
        int ready = Math.min(left.size(), right.size());
        if (ready == 0) return;
        stream.push(left.array(), right.array(), ready);
        left.dropFirst(ready);
        right.dropFirst(ready);
    }

    @Override
    public Samples finish() throws IOException {
        if (leftRate != null) {
            leftRate.finish(left);
            rightRate.finish(right);
            flushResampled();
        }
        stream.finish();
        toSpeech.finish(output);
        return output.samples();
    }
}
