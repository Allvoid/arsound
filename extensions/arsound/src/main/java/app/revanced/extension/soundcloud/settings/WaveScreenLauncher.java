package app.revanced.extension.soundcloud.settings;

import android.content.Context;
import android.content.Intent;

/** Opens the "My Wave" screen from outside the settings package (the wave button of the Library). */
public final class WaveScreenLauncher {
    private WaveScreenLauncher() {
    }

    public static Intent intent(Context context) {
        return WaveScreen.intent(context);
    }
}
