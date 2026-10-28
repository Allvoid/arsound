package app.revanced.extension.soundcloud.recommendations;

import app.revanced.extension.shared.Utils;

/** Tells the caller of a refresh what it is doing, on the main thread. The caller may have asked for nothing. */
final class RefreshProgress {
    private final ForYou.Listener listener;

    /** @param listener May be null. */
    RefreshProgress(ForYou.Listener listener) {
        this.listener = listener;
    }

    void say(String message) {
        if (listener != null) Utils.runOnMainThread(() -> listener.onProgress(message));
    }
}
