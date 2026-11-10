package app.revanced.extension.soundcloud.player;

import androidx.media3.common.audio.AudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import app.revanced.extension.soundcloud.player.dsp.SoftEffects;

/**
 * The equalizer, the preamp, the bass boost and the reverb of the player as a Media3 audio processor, put into the
 * chain of the player's audio sink by {@link SoftEqualizer}. It shapes the sound in software ({@link SoftEffects}), so it
 * works on every phone, also where the firmware has no audio effects (HyperOS). It is always in the chain and passes the
 * sound on untouched while nothing is set; the settings reach it live.
 * <p>
 * The method names are those of SoundCloud's shrunk build (see the stub of {@code AudioProcessor}).
 */
public final class SoftEqualizerProcessor implements AudioProcessor {
    private static final ByteBuffer EMPTY = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());

    private final SoftEffects effects = new SoftEffects();
    private boolean active;
    private boolean floats;
    private boolean inputEnded;
    private ByteBuffer output = EMPTY;
    private ByteBuffer buffer = EMPTY;

    SoftEqualizerProcessor() {
        effects.setParams(SoftEqualizer.params());
        SoftEqualizer.register(this);
    }

    void setParams(app.revanced.extension.soundcloud.player.dsp.EffectParams params) {
        effects.setParams(params);
    }

    /** configure: the format goes out as it came in; a stream this cannot shape (24-bit) is left alone. */
    @Override
    public AudioFormat f(AudioFormat format) {
        active = SoftEffects.supports(format.c) && format.b > 0 && format.a > 0;
        floats = format.c == SoftEffects.ENCODING_PCM_FLOAT;
        if (!active) return AudioFormat.e;
        effects.configure(format.a, format.b, format.c);
        return format;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    /** queueInput. */
    @Override
    public void b(ByteBuffer input) {
        int size = input.remaining();
        if (size == 0) return;
        if (buffer.capacity() < size) buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        buffer.clear();
        effects.process(input, buffer);
        buffer.flip();
        // The loudness for the lines of the "My Wave" tab: a sparse look at what goes on to the speaker.
        AudioLevel.feed(buffer, floats);
        output = buffer;
    }

    /** queueEndOfStream. */
    @Override
    public void c() {
        inputEnded = true;
    }

    /** getOutput: what was shaped, once. */
    @Override
    public ByteBuffer d() {
        ByteBuffer result = output;
        output = EMPTY;
        return result;
    }

    /** isEnded. */
    @Override
    public boolean a() {
        return inputEnded && output == EMPTY;
    }

    @Override
    public void flush() {
        output = EMPTY;
        inputEnded = false;
        effects.flush();
    }

    @Override
    public void reset() {
        flush();
        buffer = EMPTY;
        active = false;
    }
}
