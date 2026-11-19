package app.revanced.extension.soundcloud.network;

/**
 * The developer tool that makes the network worse on purpose, to see how the app behaves: no network, a bad network
 * (about three requests in ten fail), a slow one (every request waits more), and a free delay for every request.
 * The numbers are those of Arsound Desktop. Has no Android parts, so it is tested on the JVM; {@link NetworkPatch}
 * applies the verdict to each request.
 */
public final class FaultSimulation {
    public static final String NONE = "none";
    public static final String OFFLINE = "offline";
    public static final String BAD_NETWORK = "bad-network";
    public static final String SLOW = "slow";

    /** Slow network: added to every request. */
    public static final long SLOW_DELAY_MS = 1_500;
    /** Bad network: a request that fails fails after this much waiting. */
    public static final long BAD_NETWORK_DELAY_MS = 300;
    /** Bad network: the share of the requests that fail. */
    public static final double FAIL_RATE = 0.3;
    /** The longest free delay. */
    public static final long MAX_REQUEST_DELAY_MS = 60_000;

    private FaultSimulation() {
    }

    /** What happens to one request: how long it waits, and whether it then fails. */
    public static final class Verdict {
        public final long delayMs;
        /** Null when the request goes on; otherwise the text of the failure it ends with. */
        public final String failure;

        Verdict(long delayMs, String failure) {
            this.delayMs = delayMs;
            this.failure = failure;
        }
    }

    /** The mode stored in the settings; "none" for anything unknown. */
    public static String parse(String stored) {
        if (OFFLINE.equals(stored) || BAD_NETWORK.equals(stored) || SLOW.equals(stored)) return stored;
        return NONE;
    }

    /** The free delay kept inside what is allowed. */
    public static long clampDelay(long delayMs) {
        return Math.max(0, Math.min(delayMs, MAX_REQUEST_DELAY_MS));
    }

    /**
     * @param mode           One of the modes; anything unknown counts as none.
     * @param requestDelayMs The free delay added to every request, whatever the mode (not offline: it fails at once).
     * @param roll           A random number from 0 up to 1, decides which requests fail in the bad network.
     */
    public static Verdict decide(String mode, long requestDelayMs, double roll) {
        long delay = clampDelay(requestDelayMs);
        switch (parse(mode)) {
            case OFFLINE:
                return new Verdict(0, "Arsound: offline mode (fault simulation)");
            case BAD_NETWORK:
                if (roll < FAIL_RATE) {
                    return new Verdict(delay + BAD_NETWORK_DELAY_MS, "Arsound: bad network (fault simulation)");
                }
                return new Verdict(delay, null);
            case SLOW:
                return new Verdict(delay + SLOW_DELAY_MS, null);
            default:
                return new Verdict(delay, null);
        }
    }
}
