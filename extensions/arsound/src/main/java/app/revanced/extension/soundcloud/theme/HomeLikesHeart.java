package app.revanced.extension.soundcloud.theme;

import android.content.Context;
import android.graphics.ColorMatrixColorFilter;

import androidx.compose.ui.graphics.AndroidColorFilter_androidKt;
import androidx.compose.ui.graphics.ColorFilter;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * The orange hearts on the home screen's "Your likes" bar. SoundCloud's server sends them as a picture link, so the
 * theme's recoloured copies of the app's own pictures ({@link HeaderArtwork}) never reach them. They are recoloured as
 * they are drawn instead: the same colour matrix turns the orange into the theme's accent and keeps the dark hearts dark.
 * SoundCloud's own look keeps the orange.
 */
@SuppressWarnings("unused")
public final class HomeLikesHeart {
    private static int filterAccent;
    private static ColorFilter filter;

    private HomeLikesHeart() {
    }

    /**
     * The bit of AsyncImage's defaults mask that stands for its colour filter. The mask numbers the parameters of
     * Coil's AsyncImage as written (model, description, modifier, transform, onState, alignment, contentScale, alpha,
     * colorFilter, ...): the colour filter is the ninth, though the shrunk method takes it as its sixth. Bit 5 would be
     * the alignment, which then stays empty and crashes the drawing.
     */
    private static final int FILTER_DEFAULT = 1 << 8;

    /**
     * Injection point, right after {@link #filter}: AsyncImage's mask of the parameters left to their defaults, without
     * the colour filter, so the filter passed is used. Its default is no filter, so nothing changes when none is passed.
     */
    public static int useFilter(int defaults) {
        return defaults & ~FILTER_DEFAULT;
    }

    /**
     * Injection point, before each of the two copies of the bar draws the picture: the colour filter it is drawn with
     * (none in SoundCloud). Runs in every frame the bar is composed, so the filter is made once per accent.
     */
    public static ColorFilter filter(ColorFilter original) {
        try {
            Context context = Utils.getContext();
            if (context == null || ArsoundTheme.SOUNDCLOUD.equals(ArsoundTheme.current())) return original;
            int accent = ArsoundTheme.palette(context, "special");
            ColorFilter made = filter;
            if (made != null && accent == filterAccent) return made;
            made = AndroidColorFilter_androidKt.asComposeColorFilter(new ColorMatrixColorFilter(HeaderArtwork.solid(accent)));
            filterAccent = accent;
            filter = made;
            return made;
        } catch (Throwable ex) {
            Logger.printException(() -> "Home likes heart: not recoloured", ex);
            return original;
        }
    }
}
