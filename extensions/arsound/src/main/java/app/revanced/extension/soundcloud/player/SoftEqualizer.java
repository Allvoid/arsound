package app.revanced.extension.soundcloud.player;

import androidx.media3.common.audio.AudioProcessor;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.player.dsp.EffectParams;

/**
 * Puts the player's own equalizer ({@link SoftEqualizerProcessor}) into the chain of the audio sink, and hands the
 * processors the parameters when a setting changes. Entry point of {@code player/EqualizerPatch.kt}.
 */
@SuppressWarnings("unused")
public final class SoftEqualizer {
    private static volatile EffectParams params = EffectParams.NONE;
    private static final List<WeakReference<SoftEqualizerProcessor>> PROCESSORS = new ArrayList<>();

    private SoftEqualizer() {
    }

    /**
     * Injection point: the start of the constructor of the audio sink's processor chain. The equalizer comes first, so
     * it shapes the sound as the decoder gives it, before the speed of a slowed or sped up version is made.
     */
    public static AudioProcessor[] withProcessor(AudioProcessor[] chain) {
        try {
            publish();
            AudioProcessor[] longer = new AudioProcessor[chain.length + 1];
            longer[0] = new SoftEqualizerProcessor();
            System.arraycopy(chain, 0, longer, 1, chain.length);
            return longer;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not put the equalizer into the audio chain", ex);
            return chain;
        }
    }

    static EffectParams params() {
        return params;
    }

    static void register(SoftEqualizerProcessor processor) {
        synchronized (PROCESSORS) {
            PROCESSORS.add(new WeakReference<>(processor));
        }
    }

    /** Reads the settings and hands the result to every processor: the sound that plays now follows at once. */
    static void publish() {
        try {
            EffectParams now = AudioEqualizer.currentParams();
            params = now;
            synchronized (PROCESSORS) {
                for (Iterator<WeakReference<SoftEqualizerProcessor>> it = PROCESSORS.iterator(); it.hasNext(); ) {
                    SoftEqualizerProcessor processor = it.next().get();
                    if (processor == null) it.remove();
                    else processor.setParams(now);
                }
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not hand the equalizer its settings", ex);
        }
    }
}
