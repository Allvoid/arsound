package app.revanced.extension.soundcloud.network;

import java.util.function.Predicate;

/**
 * What the region guard knows about the route of the app: the country of its public IP address, when that was last
 * checked, and whether the user was told. A check can take seconds and the network can change meanwhile, so every
 * network change starts a new generation: the answer of a check that began in an older one is dropped.
 */
final class RegionState {
    /**
     * Guards {@link #generation} and the writes of a check's answer. Not the monitor of the guard: a check holds
     * that for the whole request, and the network callback must not wait for it.
     */
    private final Object lock = new Object();
    /** Counts network changes. An answer of a check that started before a change belongs to the old network. */
    private int generation;

    private volatile String country;
    /** When a check last failed, 0 if the last one did not. */
    private volatile long lastFailedCheck;
    private volatile long checkedAt;
    /** The blocked toast was shown for the country known now. */
    private volatile boolean noticeShown;

    /** The country code of the last known IP address, or null when it is unknown. */
    String country() {
        return country;
    }

    long lastFailedCheck() {
        return lastFailedCheck;
    }

    long checkedAt() {
        return checkedAt;
    }

    void setCheckedAt(long time) {
        checkedAt = time;
    }

    int generation() {
        synchronized (lock) {
            return generation;
        }
    }

    /** The user is told once for each country; true for the caller who has to show the toast. */
    boolean claimNotice() {
        if (noticeShown) return false;
        noticeShown = true;
        return true;
    }

    /** Forgets a failed check, so the next request checks at once, and lets the toast show again. */
    void forgetFailure() {
        lastFailedCheck = 0;
        noticeShown = false;
    }

    /** The network changed: forgets the known country, and answers of checks that are still running are dropped. */
    void networkChanged(boolean resetFailedCheck) {
        synchronized (lock) {
            generation++;
            country = null;
            if (resetFailedCheck) forgetFailure();
        }
    }

    /**
     * Keeps the answer of a check, unless the network changed while it ran. A failed check is remembered, so a dead
     * network does not cause a check per request.
     *
     * @param started The generation when the check began.
     * @param fresh   The country found, or null if the check failed.
     * @return False if the answer belongs to an older network and was dropped.
     */
    boolean publishCheck(int started, String fresh) {
        synchronized (lock) {
            if (started != generation) return false;
            if (fresh != null) country = fresh;
            else lastFailedCheck = System.currentTimeMillis();
            return true;
        }
    }

    /**
     * Keeps the answer of a check that ran in the background while requests used the known country.
     *
     * @param started   The generation when the check began.
     * @param previous  The country known when the check began.
     * @param fresh     The country found, or null if the check failed.
     * @param isBlocked Tells whether a country is one the user blocked.
     */
    void publishBackgroundCheck(int started, String previous, String fresh, Predicate<String> isBlocked) {
        synchronized (lock) {
            // The network changed during the check: its answer belongs to the old one and must not replace
            // the state of the new one. The unknown country makes the next request check again.
            if (started != generation) return;
            if (fresh != null) {
                country = fresh;
                if (!fresh.equals(previous)) noticeShown = false;
            } else if (previous != null && !isBlocked.test(previous)) {
                // A failed check on an allowed network means the route is unstable, and a VPN may be
                // reconnecting. Requests wait for a successful check instead of trusting the old answer.
                country = null;
                lastFailedCheck = System.currentTimeMillis();
                noticeShown = false;
            }
        }
    }
}
