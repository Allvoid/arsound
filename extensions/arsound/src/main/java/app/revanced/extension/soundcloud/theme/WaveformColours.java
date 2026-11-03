package app.revanced.extension.soundcloud.theme;

import android.content.Context;
import android.graphics.Paint;
import android.view.View;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Colors;

/**
 * The played part of the waveform in the player is drawn in colours that SoundCloud's style writes as plain numbers
 * ({@code #ff5500} and {@code #ffbf99}), not as colour resources, so the theme's colour table cannot reach them: the
 * waveform stayed orange under every theme. The paints are recoloured when the view gets its size.
 */
@SuppressWarnings("unused")
public final class WaveformColours {
    private WaveformColours() {
    }

    /**
     * Injection point: the start of {@code WaveformView.onSizeChanged} (the constructor cannot be used: its register of the view is reused). {@code w} and {@code x} are the paints of the played
     * part above and below the line (the names are those of SoundCloud's shrunk build).
     */
    public static void recolour(View waveform) {
        try {
            Context context = waveform.getContext();
            int[] colours = ArsoundTheme.darkColors(context);
            // The original look keeps SoundCloud's own orange.
            if (colours == null) return;
            int accent = colours[1];
            paint(waveform, "w").setColor(accent);
            paint(waveform, "x").setColor(Colors.blend(accent, 0xFFFFFFFF, 0.55f));
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not recolour the waveform", ex);
        }
    }

    private static Paint paint(View waveform, String field) throws ReflectiveOperationException {
        java.lang.reflect.Field found = waveform.getClass().getDeclaredField(field);
        found.setAccessible(true);
        return (Paint) found.get(waveform);
    }
}
