package app.revanced.extension.soundcloud.lyrics.transcribe;

/** The whole song for the speech model: the channels averaged and resampled to 16 kHz. */
final class MonoTarget implements PcmTarget {
    private final GrowableFloats output = new GrowableFloats(Chunker.RATE * 60);
    private StreamResampler resampler;
    private int channels;
    private float[] mono = new float[0];

    @Override
    public void start(int rate, int channels) {
        this.channels = channels;
        resampler = new StreamResampler(rate, Chunker.RATE);
    }

    @Override
    public void push(float[] interleaved, int frames) {
        if (mono.length < frames) mono = new float[frames];
        PcmConverter.monoFromFloats(interleaved, frames, channels, mono);
        resampler.push(mono, frames, output);
    }

    @Override
    public float[] finish() {
        if (resampler != null) resampler.finish(output);
        return output.toArray();
    }
}
