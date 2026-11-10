package app.revanced.extension.soundcloud.widget;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import app.revanced.extension.shared.Logger;

/**
 * An invisible screen behind a button of the player widget on phones where the launcher's broadcast does not reach the
 * app ({@link WidgetButtons}). A tap that opens a screen does go through, so the button opens this one; it takes the
 * broadcast SoundCloud meant for its receiver, sends it from inside the app, where it is delivered, and closes at once,
 * before it is ever drawn (no display, no animation). The broadcast is the original one, marked as relayed, so
 * SoundCloud's receiver does what it always does.
 */
public final class WidgetRelayActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        long start = SystemClock.elapsedRealtime();
        try {
            Intent original = getIntent() == null ? null : getIntent().getParcelableExtra(WidgetButtons.ORIGINAL_EXTRA);
            if (original == null) {
                WidgetLog.append(this, "relay: a tap with no broadcast to pass on");
            } else {
                original.putExtra(WidgetButtons.RELAYED_EXTRA, SystemClock.elapsedRealtime());
                sendBroadcast(original);
                String line = "relay: passed on " + original.getAction() + " in " + (SystemClock.elapsedRealtime() - start) + " ms";
                Logger.printInfo(() -> "Widget: " + line);
                WidgetLog.append(this, line);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: the relay could not pass the tap on", ex);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
