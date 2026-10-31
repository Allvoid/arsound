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
    /** The uid of the adb shell. */
    private static final int SHELL_UID = 2000;

    @Override
    public void onReceive(Context context, Intent intent) {
        // The receiver is exported, so any app may send to it; from Android 14 the sender is known, and only the shell
        // is answered. Before that the work itself needs WRITE_SECURE_SETTINGS, which only adb grants.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            int sender = getSentFromUid();
            if (sender != -1 && sender != SHELL_UID && sender != android.os.Process.myUid()) {
                Logger.printInfo(() -> "vivo music player list: refused a broadcast from uid " + sender);
                return;
            }
        }
        VivoMusicCard.State state = VivoMusicCard.canWrite(context)
                ? VivoMusicCard.addToList(context) : VivoMusicCard.State.NOT_LISTED;
        Logger.printInfo(() -> "vivo music player list from adb: " + state);
        setResultData("Arsound: " + state);
    }
}
