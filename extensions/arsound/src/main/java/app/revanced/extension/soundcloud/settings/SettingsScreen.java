package app.revanced.extension.soundcloud.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

/**
 * One screen of the settings. The Activity only shows it and passes on what happens to it (back, resume, results of
 * other screens); each kind of screen answers for itself.
 */
abstract class SettingsScreen {
    final ReVancedSettingsActivity activity;
    final SettingsUi ui;

    SettingsScreen(SettingsUi ui) {
        this.ui = ui;
        this.activity = ui.activity;
    }

    /** The views of the screen. */
    abstract View create();

    /** Scrolls to the row the search led to and lets it glow; screens without a list ignore it. */
    void revealRow(String title) {
    }

    /** True if the screen took the back press (a closing search, say). */
    boolean onBackPressed() {
        return false;
    }

    void onResume() {
    }

    void onRestart() {
    }

    void onDestroy() {
    }

    /** What the screen keeps for the system to hand back when the Activity is made again. */
    void onSaveInstanceState(Bundle outState) {
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
    }
}
