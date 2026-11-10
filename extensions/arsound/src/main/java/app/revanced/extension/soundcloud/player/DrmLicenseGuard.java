package app.revanced.extension.soundcloud.player;

import android.media.MediaDrm;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import app.revanced.extension.shared.Logger;

/**
 * Stops tracks that pause themselves right after the tap on a phone whose DRM store lost a saved licence.
 * <p>
 * Some SoundCloud tracks are encrypted (the stream type {@code ctr-encrypted-hls}). After such a track is played once,
 * SoundCloud downloads a licence for it in the background, keeps the licence's key set id in its own database and
 * plays the track from that licence next time ({@code ShortLivedLicenseRepository}). The licence itself lives in the
 * phone's Widevine store, outside the app. When the store no longer has it (a phone that does not keep saved licences,
 * a store cleared by the system), restoring it fails with {@code cdmError 347 BAD_VALUE}, the player stops with a DRM
 * error and the track pauses. SoundCloud keeps the dead key set id, so the same track fails again on every tap.
 * <p>
 * Here the saved key set id is tried before it is given to the player, the same way the player would use it. A dead one
 * is dropped: SoundCloud then asks for a new licence from the server, as it does for a track played the first time.
 * After the first dead one SoundCloud also stops saving licences for the rest of the run, so a phone that cannot keep
 * them does not download a licence in the background for every track.
 */
@SuppressWarnings("unused")
public final class DrmLicenseGuard {
    /** The Widevine scheme, the same id the player uses. */
    private static final UUID WIDEVINE = new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);

    /** Opens a DRM session and restores a saved licence in it. */
    interface Probe {
        /** Throws {@link IllegalArgumentException} when the store has no such licence. */
        void restore(byte[] keySetId) throws Exception;
    }

    /** The answers for key set ids tried in this run; a licence is checked once. */
    private static final Map<String, Boolean> answers = new HashMap<>();
    /** A saved licence was found dead in this run: saving more of them would only repeat the failure. */
    private static volatile boolean storeLosesLicences;

    private DrmLicenseGuard() {
    }

    /**
     * Injection point: the saved key set id SoundCloud found for a stream, on its way to the player.
     *
     * @return The same key set id when the phone can restore it (or when that cannot be told), else null.
     */
    public static byte[] restorable(byte[] keySetId) {
        if (keySetId == null) return null;
        try {
            byte[] result = restorable(keySetId, DrmLicenseGuard::restoreInNewSession);
            if (result == null) {
                Logger.printInfo(() -> "The saved DRM licence is not in the phone's store: playing with a new one");
            }
            return result;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not check the saved DRM licence", ex);
            return keySetId;
        }
    }

    /**
     * Injection point: whether SoundCloud should stop saving licences, because the phone has already lost one.
     */
    public static boolean storeLosesLicences() {
        return storeLosesLicences;
    }

    static byte[] restorable(byte[] keySetId, Probe probe) {
        String key = new String(keySetId, StandardCharsets.ISO_8859_1);
        Boolean known;
        synchronized (answers) {
            known = answers.get(key);
        }
        boolean usable;
        if (known != null) {
            usable = known;
        } else {
            usable = canRestore(keySetId, probe);
            synchronized (answers) {
                answers.put(key, usable);
            }
        }
        return usable ? keySetId : null;
    }

    private static boolean canRestore(byte[] keySetId, Probe probe) {
        try {
            probe.restore(keySetId);
            return true;
        } catch (IllegalArgumentException missing) {
            // BAD_VALUE: the same error that stops the player.
            storeLosesLicences = true;
            return false;
        } catch (Exception other) {
            // Busy, not provisioned, no Widevine: not the dead licence. The player decides on its own.
            return true;
        }
    }

    private static void restoreInNewSession(byte[] keySetId) throws Exception {
        MediaDrm drm = new MediaDrm(WIDEVINE);
        byte[] session = null;
        try {
            session = drm.openSession();
            drm.restoreKeys(session, keySetId);
        } finally {
            if (session != null) {
                try {
                    drm.closeSession(session);
                } catch (Exception ignored) {
                    // The session goes away with the object.
                }
            }
            drm.release();
        }
    }

    static void forgetAnswers() {
        synchronized (answers) {
            answers.clear();
        }
        storeLosesLicences = false;
    }
}
