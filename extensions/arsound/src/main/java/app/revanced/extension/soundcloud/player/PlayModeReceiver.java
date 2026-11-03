package app.revanced.extension.soundcloud.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import app.revanced.extension.soundcloud.recommendations.DislikeAction;

/** The play mode button of the media notification on Android 12L and older. Not exported: only the notification calls it. */
public final class PlayModeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (PlayModeButton.ACTION.equals(intent.getAction())) PlayModeButton.cycle();
        else if (PlayModeButton.DISLIKE_ACTION.equals(intent.getAction())) DislikeAction.run(context);
    }
}
