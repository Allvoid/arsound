package app.revanced.extension.soundcloud.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.view.View;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.theme.ThemeBackdrop;
import app.revanced.extension.soundcloud.theme.ThemeEffect;
import app.revanced.extension.soundcloud.wallpaper.WallpaperStore;

/**
 * Settings screen of the ReVanced SoundCloud patches.
 * <p>
 * Only the shell of the screens: it shows the {@link SettingsScreen} the intent asks for and passes on what happens to
 * it (back, resume, results of other screens). The screens are built of plain Android views by {@link SettingsUi},
 * every visual detail of which (theme colors, fonts, text styles, toggle row layout, icons, spacing) is taken from the
 * SoundCloud resources by name, so the screen follows the app look, including the dark theme.
 */
@SuppressWarnings("unused")
public final class ReVancedSettingsActivity extends Activity {
    /** Which screen to open; none means the main one. */
    static final String EXTRA_SCREEN = "arsound_screen";
    /** The title of a row to scroll to and let glow once the screen is shown (the search sets it). */
    static final String EXTRA_HIGHLIGHT = "arsound_highlight";

    private SettingsScreen screen;
    /** What the system kept of this screen when it made it again (a fold, a split screen, a killed process), or null. */
    private Bundle restoredState;
    /** What the wallpaper looked like when this screen was built; a screen opened before a change is built again. */
    private String wallpaperStamp = WallpaperStore.stamp();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Dark like SoundCloud's own screens, whatever the phone's mode.
        ArsoundTheme.forceDark(this, base);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoredState = savedInstanceState;

        try {
            screen = SettingsScreens.open(new SettingsUi(this), getIntent().getStringExtra(EXTRA_SCREEN));
            View content = screen.create();
            addThemeEffect(content);
            setContentView(content);
            String highlight = getIntent().getStringExtra(EXTRA_HIGHLIGHT);
            if (highlight != null) content.postDelayed(() -> screen.revealRow(highlight), 250);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to create ReVanced settings screen", ex);
            finish();
        }
    }

    /** The chosen theme's animation behind the screen, over its background colour and glow. */
    private void addThemeEffect(View root) {
        ThemeEffect effect = ThemeBackdrop.forCurrentTheme(this, 1f);
        if (effect == null) return;
        Drawable background = root.getBackground();
        root.setBackground(background == null ? effect : new LayerDrawable(new Drawable[]{background, effect}));
    }

    /** What the system kept of this screen the last time, see {@link SettingsScreen#onSaveInstanceState}. */
    Bundle restoredState() {
        return restoredState;
    }

    /** This screen shows the wallpaper as it is now (it changed the wallpaper itself), so it need not be built again. */
    void markWallpaperSeen() {
        wallpaperStamp = WallpaperStore.stamp();
    }

    @Override
    public void onBackPressed() {
        if (screen != null && screen.onBackPressed()) return;
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screen != null) screen.onResume();
        // The wallpaper was chosen, switched off or changed on a screen opened later.
        if (!wallpaperStamp.equals(WallpaperStore.stamp())) recreate();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (screen != null) screen.onRestart();
    }

    @Override
    protected void onDestroy() {
        if (screen != null) screen.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (screen != null) screen.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (screen != null) screen.onActivityResult(requestCode, resultCode, data);
    }
}
