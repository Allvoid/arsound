package app.revanced.extension.soundcloud.player;

import android.media.audiofx.DynamicsProcessing;
import android.os.Build;

import app.revanced.extension.shared.Logger;

/**
 * The preamp of one audio session: Android's dynamics effect (Android 9 and later) used for its input gain, with its
 * limiter on while the sound is made louder, so loud tracks do not distort.
 * <p>
 * The effect is held as an {@code Object} and cast only after the version is checked, so the class loads on the
 * Android versions that do not have {@link DynamicsProcessing}.
 */
final class PreampEffect {
    private final int sessionId;
    /** The {@link DynamicsProcessing}, made on the first gain other than zero; null before that. */
    private Object effect;
    /** The phone refused the effect: the preamp moves the equalizer bands instead. */
    private boolean failed;

    PreampEffect(int sessionId) {
        this.sessionId = sessionId;
    }

    /** Whether the effect has been made, so it counts among the effects the system allows. */
    boolean exists() {
        return effect != null;
    }

    /**
     * Sets the gain.
     *
     * @return False if the effect is not available and the gain must go on the equalizer bands.
     */
    boolean apply(int preamp) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || failed) return false;
        try {
            DynamicsProcessing gain = (DynamicsProcessing) effect;
            if (gain == null) {
                // No gain asked yet: the effect is not made at all, so the sound path stays as SoundCloud has it.
                if (preamp == 0) return true;
                DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        false, 0, false, 0, false, 0, true).build();
                gain = new DynamicsProcessing(0, sessionId, config);
                effect = gain;
            }
            gain.setInputGainAllChannelsTo(preamp / 100f);
            gain.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, preamp > 0, 0, 1f, 60f, 10f, -1f, 0f));
            gain.setEnabled(preamp != 0);
            return true;
        } catch (Throwable ex) {
            Logger.printException(() -> "Preamp: the dynamics effect is not available, using the equalizer bands", ex);
            failed = true;
            // A gain set before the failure would add to the one moved onto the bands.
            try {
                if (effect != null) ((DynamicsProcessing) effect).setEnabled(false);
            } catch (Throwable ignored) {
                // Released already.
            }
            return false;
        }
    }

    void release() {
        if (effect != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ((DynamicsProcessing) effect).release();
            } catch (Throwable ignored) {
                // Already released by the system.
            }
        }
    }
}
