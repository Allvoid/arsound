package app.revanced.extension.soundcloud.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The play mode button of the media notification on Android 12L and older. Not exported: only the notification calls it. */
public final class PlayModeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && PlayModeButton.ACTION.equals(intent.getAction())) PlayModeButton.cycle();
    }
}
