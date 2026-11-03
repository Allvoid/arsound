package app.revanced.extension.soundcloud.recommendations;

import android.content.Context;
import android.content.SharedPreferences;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.player.PlaybackEvents;

/**
 * Keeps what the player learned about the taste ({@link WaveLedger}) in the preferences of this phone and reads each
 * finished listen into it. The player's statistics call {@link #onTrackStarted} and {@link #onListen}; the wave reads
 * {@link #ledger()}.
 * <p>
 * The hooks run on the player's thread and must not wait for the disk: they only hand the listen to
 * {@link WaveLearner}, and a background thread loads the ledger, fills it and saves it. The ledger is loaded in the
 * background as soon as a track starts ({@link #warmUp}).
 */
public final class WaveMemory {
    private static final String PREFERENCES_NAME = "arsound_wave";
    private static final String LEDGER = "ledger";
    private static final WaveLearner LEARNER = new WaveLearner();
    /** Guards reading, changing and saving the ledger (the disk work). Never taken on the player's thread. */
    private static final Object DISK = new Object();
    /** Null until it is read from the preferences. */
    private static volatile WaveLedger ledger;

    private WaveMemory() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /**
     * The ledger read from the preferences, null while there is no context to read them with (then nothing is kept in
     * memory, or an empty ledger would stay and be saved over the real one).
     */
    private static WaveLedger loaded() {
        WaveLedger current = ledger;
        if (current != null) return current;
        synchronized (DISK) {
            if (ledger == null) {
                SharedPreferences preferences = preferences();
                if (preferences == null) return null;
                ledger = WaveLedger.parse(preferences.getString(LEDGER, ""));
            }
            return ledger;
        }
    }

    /**
     * What was learned. May read the preferences the first time, so not for the player's thread: the wave builder and
     * the settings call it.
     */
    static WaveLedger ledger() {
        WaveLedger current = loaded();
        return current != null ? current : new WaveLedger();
    }

    /** Reads the ledger on a background thread if it is not read yet, so the settings and the wave find it ready. */
    public static void warmUp() {
        if (ledger != null) return;
        Utils.runOnBackgroundThread(WaveMemory::loaded);
    }

    /**
     * A track starts. Its length, if SoundCloud knows it, is kept until the listen ends: only then the wave can tell a
     * short track heard to its end from one switched off. Player thread; never blocks.
     *
     * @param durationMs The length of the track, 0 if not known.
     */
    public static void onTrackStarted(String urn, long durationMs) {
        try {
            LEARNER.noteLength(urn, durationMs);
            warmUp();
        } catch (Throwable ex) {
            Logger.printException(() -> "My Wave: could not note the track", ex);
        }
    }

    /**
     * The player's position report ({@code PlaybackEvents.onProgress}), about twice a second. The length it carries is
     * the exact one of the playing track, better than SoundCloud's data given at the start (a preview, or a track
     * whose data had no length). Player thread; never blocks.
     */
    public static void onProgress(long durationMs) {
        try {
            LEARNER.noteLength(PlaybackEvents.playingUrn(), durationMs);
        } catch (Throwable ex) {
            Logger.printException(() -> "My Wave: could not note the length", ex);
        }
    }

    /**
     * A listen ended: a track switched off in the first seconds is remembered as skipped, one heard to a good length as
     * heard. Only SoundCloud tracks count. Player thread; the ledger is filled in the background.
     */
    public static void onListen(String urn, long playedMs) {
        try {
            if (!LEARNER.listenEnded(urn, playedMs, System.currentTimeMillis())) return;
            Utils.runOnBackgroundThread(WaveMemory::learnWaiting);
        } catch (Throwable ex) {
            Logger.printException(() -> "My Wave: could not note the listen", ex);
        }
    }

    /** Puts the waiting listens into the ledger and saves it. Background thread. */
    private static void learnWaiting() {
        synchronized (DISK) {
            // No context yet: the listens wait for the next time.
            WaveLedger kept = loaded();
            if (kept == null) return;
            if (LEARNER.applyTo(kept)) save(kept);
        }
    }

    private static void save(WaveLedger kept) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putString(LEDGER, kept.format()).apply();
    }

    /** Forgets what was learned: the skipped and the heard tracks. */
    public static void forget() {
        synchronized (DISK) {
            LEARNER.dropWaiting();
            ledger = new WaveLedger();
            SharedPreferences preferences = preferences();
            if (preferences != null) preferences.edit().remove(LEDGER).apply();
        }
    }

    /** How many tracks are remembered as skipped, for the settings. */
    public static int skippedCount() {
        return ledger().skipped().size();
    }

    /** How many tracks are remembered as heard. */
    public static int heardCount() {
        return ledger().heardNewestFirst().size();
    }
}
