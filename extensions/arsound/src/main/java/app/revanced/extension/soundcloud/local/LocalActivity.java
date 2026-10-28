package app.revanced.extension.soundcloud.local;

import android.app.Activity;
import android.content.Context;

import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/** The base of Arsound's invisible screens of the local music: dark like SoundCloud's own screens. */
abstract class LocalActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Dark like SoundCloud's own screens, whatever the phone's mode.
        ArsoundTheme.forceDark(this, base);
    }
}
