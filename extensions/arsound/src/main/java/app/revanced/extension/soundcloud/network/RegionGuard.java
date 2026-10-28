package app.revanced.extension.soundcloud.network;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Stops all requests to SoundCloud while the app goes online from an IP address of a country the user chose
 * (Russia by default; Ukraine, Belarus, Kazakhstan and any other country code can be added).
 * <p>
 * The country is checked through a neutral service (Cloudflare trace, {@link CountryTrace}), over the same route the
 * app uses, including a VPN. Until the check finishes, requests wait for it instead of leaking the
 * address. The result is kept until the network changes ({@link RegionState}, {@link NetworkWatcher}).
 */
public final class RegionGuard {
    /**
     * A failed country check is not repeated for this long, so a dead network does not cause a check per request.
     * Short: right after launch Android blocks the network of an app that is not yet on screen, and a long pause
     * made SoundCloud show "No internet connection" on start.
     */
    private static final long FAILED_CHECK_BACKOFF_MS = 3_000;
    /** Blocked requests are answered after this pause, so SoundCloud's retry loops cannot spin the radio and CPU. */
    private static final long BLOCKED_RESPONSE_DELAY_MS = 1_500;
    /** The known answer is re-checked in the background this often, so a new IP on the same network is noticed. */
    private static final long BLOCKED_RECHECK_MS = 30_000;

    private static final RegionState state = new RegionState();
    private static final NetworkWatcher watcher = new NetworkWatcher(new NetworkWatcher.Listener() {
        @Override
        public void onNetworkReplaced() {
            // Turned off by the user: the known country stays until a check by hand.
            if (!Settings.isRegionRecheckOnChangeEnabled()) return;
            state.networkChanged(true);
        }

        @Override
        public void onLinkChanged() {
            if (!Settings.isRegionRecheckOnChangeEnabled()) return;
            // A new local address or DNS on the same network may mean a new public IP. This event is
            // frequent, so requests keep the known answer while it is checked again in the background.
            state.setCheckedAt(0);
            recheckIfStaleNow();
        }

        @Override
        public void onNetworkLost() {
            if (!Settings.isRegionRecheckOnChangeEnabled()) return;
            state.networkChanged(false);
        }
    });

    private static volatile boolean recheckRunning;
    /** Only one background check waits in the pool for a connectivity query; see {@link #isConnected}. */
    private static final AtomicBoolean checkQueued = new AtomicBoolean();

    private RegionGuard() {
    }

    public static String lastCountry() {
        return state.country();
    }

    /** True while requests are blocked because the last known country is one of the chosen ones. */
    public static boolean blocksLastCountry() {
        String current = state.country();
        return current != null && active() && Settings.getRegionGuardCountries().contains(current);
    }

    /** The guard is on and at least one country is chosen. */
    private static boolean active() {
        return Settings.isRegionGuardEnabled() && !Settings.getRegionGuardCountries().isEmpty();
    }

    private static boolean isBlockedCountry(String code) {
        return Settings.getRegionGuardCountries().contains(code);
    }

    /** @return True if the request to this host must not be sent. Called on network threads. */
    public static boolean shouldBlock(String host) {
        if (host == null || !active()) return false;
        if (!isSoundCloudHost(host)) return false;
        return isBlockedNow();
    }

    /** Requests that bypass the host check: the Arsound search sends them to YouTube, not SoundCloud. */
    public static void throwIfBlockedAnyHost() throws IOException {
        if (active() && isBlockedNow()) {
            throw new BlockedException("Arsound: requests are blocked from an IP address of a chosen country");
        }
    }

    /** Thrown instead of sending a request while the app is on an IP of a chosen country or the country is unknown. */
    public static final class BlockedException extends IOException {
        BlockedException(String message) {
            super(message);
        }
    }

    private static boolean isBlockedNow() {
        watcher.start();
        String current = state.country();
        if (current == null && System.currentTimeMillis() - state.lastFailedCheck() > FAILED_CHECK_BACKOFF_MS) {
            current = check();
        }
        boolean blocked = current == null || isBlockedCountry(current);
        // An allowed answer is checked again too: a VPN that loses its tunnel on a bad network can send
        // requests directly without the network changing, and the old answer would let them through.
        recheckIfStale();
        if (blocked && state.claimNotice()) BlockNotice.show(current);
        return blocked;
    }

    public static void throwIfBlocked(String host) throws IOException {
        if (shouldBlock(host)) {
            try {
                Thread.sleep(BLOCKED_RESPONSE_DELAY_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            throw new BlockedException("Arsound: requests to SoundCloud are blocked from an IP address of a chosen country");
        }
    }

    /**
     * Called from SoundCloud's "is the network connected" check. While requests are blocked the app
     * behaves as offline: it stops syncing and retrying, shows downloaded content and saves battery.
     * Never checks on the calling thread; an unknown country starts a background check.
     */
    public static boolean isConnected(boolean connected) {
        if (!connected || !active()) return connected;
        String current = state.country();
        if (current != null) {
            if (!isBlockedCountry(current)) return true;
            recheckIfStale();
            return false;
        }
        // Unknown yet: report the network as connected, the requests themselves wait for the check.
        // This is asked for every list row and playback event, so one queued check is enough: the others would
        // only park on its lock, each in a thread of its own. A failed check is not repeated inside the backoff.
        if (System.currentTimeMillis() - state.lastFailedCheck() > FAILED_CHECK_BACKOFF_MS && checkQueued.compareAndSet(false, true)) {
            try {
                Utils.runOnBackgroundThread(() -> {
                    try {
                        check();
                    } finally {
                        checkQueued.set(false);
                    }
                });
            } catch (Throwable t) {
                // A thread that cannot be started must not reach SoundCloud's connectivity method.
                checkQueued.set(false);
                Logger.printException(() -> "Region guard: could not start the country check", t);
            }
        }
        return true;
    }

    private static boolean isSoundCloudHost(String host) {
        host = host.toLowerCase(Locale.US);
        return host.endsWith("soundcloud.com") || host.endsWith("sndcdn.com") || host.endsWith("soundcloud.cloud")
                || host.endsWith("snd.sc");
    }

    /**
     * Forgets the known country and checks it again in the background, for example after the user
     * switched a VPN on the same network. The callback, if any, runs on the main thread.
     */
    public static void recheck(Runnable onDone) {
        Utils.runOnBackgroundThread(() -> {
            state.forgetFailure();
            fetchAndPublish();
            if (onDone != null) Utils.runOnMainThread(onDone);
        });
    }

    /** The check every 30 seconds; the user may turn it off and check by hand. */
    private static void recheckIfStale() {
        if (!Settings.isRegionRecheckPeriodicEnabled()) return;
        recheckIfStaleNow();
    }

    private static void recheckIfStaleNow() {
        if (recheckRunning || System.currentTimeMillis() - state.checkedAt() < BLOCKED_RECHECK_MS) return;
        recheckRunning = true;
        Utils.runOnBackgroundThread(() -> {
            try {
                int started = state.generation();
                String previous = state.country();
                String fresh = fetchCountry();
                state.publishBackgroundCheck(started, previous, fresh, RegionGuard::isBlockedCountry);
            } finally {
                recheckRunning = false;
            }
        });
    }

    /** Checks the country once for all waiting requests. Null means the check failed. */
    private static synchronized String check() {
        String known = state.country();
        if (known != null) return known;
        // Threads that queued on this lock while one check failed take its answer instead of each running a check
        // of 6 s connect and 6 s read of their own.
        long failedAt = state.lastFailedCheck();
        if (failedAt != 0 && System.currentTimeMillis() - failedAt <= FAILED_CHECK_BACKOFF_MS) return null;
        return fetchAndPublish();
    }

    /**
     * Checks the country and keeps the answer, unless the network changed while the check ran: then the answer
     * is for the old network, and the check is made once more. Null means the check failed.
     */
    private static String fetchAndPublish() {
        for (int attempt = 0; attempt < 2; attempt++) {
            int started = state.generation();
            String fresh = fetchCountry();
            if (state.publishCheck(started, fresh)) return fresh;
        }
        return null;
    }

    private static String fetchCountry() {
        CountryTrace.waitUntilForeground();
        state.setCheckedAt(System.currentTimeMillis());
        return CountryTrace.request();
    }

    /** "российский IP", "Ukrainian IP", "IP страны DE": the country of the address in words. */
    public static String describeIp(String code, boolean russian) {
        return IpCountry.describe(code, russian);
    }
}
