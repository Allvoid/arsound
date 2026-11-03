package app.revanced.extension.soundcloud.recommendations;

import android.content.Context;
import android.content.SharedPreferences;

import app.revanced.extension.shared.Utils;

/**
 * Keeps what the player learned about the taste ({@link WaveLedger}) in the preferences of this phone and reads each
 * finished listen into it. The player's statistics call {@link #onListen}; the wave reads {@link #ledger}.
 */
public final class WaveMemory {
    private static final String PREFERENCES_NAME = "arsound_wave";
    private static final String LEDGER = "ledger";
    private static WaveLedger ledger;

    private WaveMemory() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    static synchronized WaveLedger ledger() {
        if (ledger == null) {
            SharedPreferences preferences = preferences();
            ledger = WaveLedger.parse(preferences == null ? "" : preferences.getString(LEDGER, ""));
        }
        return ledger;
    }

    /**
     * A listen ended: a track switched off in the first seconds is remembered as skipped, one heard to a good length as
     * heard. Only SoundCloud tracks count.
     *
     * @param durationMs The length of the track, 0 if not known.
     */
    public static void onListen(String urn, long playedMs, long durationMs) {
        if (urn == null || !urn.contains(":tracks:")) return;
        ListenVerdict verdict = ListenVerdict.of(playedMs, durationMs);
        if (verdict == ListenVerdict.BETWEEN) return;
        WaveLedger kept = ledger();
        kept.record(TrackUrn.normalize(urn), verdict, System.currentTimeMillis());
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putString(LEDGER, kept.format()).apply();
    }

    /** Forgets what was learned: the skipped and the heard tracks. */
    public static synchronized void forget() {
        ledger = new WaveLedger();
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().remove(LEDGER).apply();
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
