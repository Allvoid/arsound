package app.revanced.extension.soundcloud.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import app.revanced.extension.shared.Logger;

/**
 * Woken by the command of {@link VivoMusicCard} over adb, right after it grants the permission, and adds Arsound to
 * the vivo music player's list. Only a sender that holds WRITE_SECURE_SETTINGS itself (the adb shell) may wake it.
 * The result shows in the output of "am broadcast".
 */
public final class VivoMusicCardReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        VivoMusicCard.State state = VivoMusicCard.canWrite(context)
                ? VivoMusicCard.addToList(context) : VivoMusicCard.State.NOT_LISTED;
        Logger.printInfo(() -> "vivo music player list from adb: " + state);
        setResultData("Arsound: " + state);
    }
}
