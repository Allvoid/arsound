package app.revanced.extension.soundcloud.shared;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;

/**
 * Takes the fill off a view so that what is behind it (the wallpaper, the screen's glow) shows through.
 * <p>
 * The view gets a clear colour, never no background at all: SoundCloud's {@code ToolbarAlphaBehavior} fades a
 * toolbar in by setting the alpha of its background and crashes when the background is null. A clear colour stays
 * clear at any alpha.
 */
public final class ClearBackground {
    private ClearBackground() {
    }

    public static void on(View view) {
        view.setBackground(new ColorDrawable(Color.TRANSPARENT));
    }
}
