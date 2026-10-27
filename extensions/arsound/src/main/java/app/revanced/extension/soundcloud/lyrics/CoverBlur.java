package app.revanced.extension.soundcloud.lyrics;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import androidx.annotation.RequiresApi;

/**
 * Blurs the cover behind the lyrics. Android 12 and later blur a view on the graphics chip with a render effect, so
 * the radius can follow an animation frame by frame; older versions cannot, and the lyrics use a darker veil there.
 */
final class CoverBlur {
    private CoverBlur() {
    }

    /** Whether this Android can blur a view. */
    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** Blurs the view and everything in it; a radius of 0 takes the blur off. */
    static void set(View view, float radiusPx) {
        if (isSupported()) Api31.set(view, radiusPx);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static final class Api31 {
        static void set(View view, float radiusPx) {
            view.setRenderEffect(radiusPx < 0.5f ? null
                    : RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP));
        }
    }
}
