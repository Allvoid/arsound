package app.revanced.extension.soundcloud.scrobble;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.player.ListeningStats;
import app.revanced.extension.soundcloud.shared.Lang;

/**
 * Scrobbling to ListenBrainz and to Last.fm, each off until the user turns it on in the settings and connects an
 * account (a token for ListenBrainz, an approval on last.fm for Last.fm). The player hooks of {@link ListeningStats} tell
 * it when a track starts and when playing starts or stops, and it keeps the rule of the services:
 * <ul>
 * <li>a listen counts once the track has been heard for half of its length or four minutes, whichever is less, and the
 * track is at least 30 seconds long ({@link HeardTime});</li>
 * <li>the listen carries the time it started;</li>
 * <li>at the start of a track the services are told what is playing now (best effort, nothing is kept of it).</li>
 * </ul>
 * Each service has a queue of its own ({@link ListenQueue}, at most 100 listens, kept in the preferences file). A listen
 * a service cannot take now (no network, a busy service, a rate limit) stays and is asked again a minute later and with
 * the next listen and at the next start; a refused credential stops that service with one note until it changes; a listen
 * the service calls invalid is forgotten. Switching a service off empties its queue.
 * <p>
 * Threads: the hooks only post to {@code STATE}, a single thread that owns the listen in progress; everything that
 * touches the network or the names of the track runs on {@code NET}, a single thread too. The queues are shared and
 * guarded by {@code LOCK}. Nothing here runs on the main thread except the note shown to the user.
 */
public final class Scrobbler {
    /** The two services. */
    public enum Service {
        LISTENBRAINZ, LASTFM
    }

    /** What a connection step found. */
    public enum Status {
        OK, INVALID, OFFLINE, NOT_SET_UP, NOT_APPROVED
    }

    /** The answer of {@link #saveListenBrainzToken}, {@link #lastFmBegin} and {@link #lastFmFinish}. */
    public static final class Check {
        public final Status status;
        /** The user name after a success; the address of the approval page for {@link #lastFmBegin}. */
        public final String value;
        /** The approval token of {@link #lastFmBegin}. */
        public final String token;

        Check(Status status, String value, String token) {
            this.status = status;
            this.value = value;
            this.token = token;
        }
    }

    private static final String PREFERENCES = "arsound_scrobble";
    /** The listen counted last (its track and start time), so a listen taken up again after a restart is not counted twice. */
    private static final String COUNTED = "counted_listen";
    private static final long RETRY_AFTER_SECONDS = 60;
    /** How many times the names of a track are asked for before its listen is given up (the first ask included). */
    private static final int NAME_TRIES = 5;
    /** Last.fm refuses a listen older than two weeks. */
    private static final long LAST_FM_OLDEST_SECONDS = 14L * 24 * 3600;

    private static final Object LOCK = new Object();
    private static final ScheduledThreadPoolExecutor STATE = new ScheduledThreadPoolExecutor(1, daemon("Arsound scrobble state"));
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(daemon("Arsound scrobble net"));

    static {
        STATE.setRemoveOnCancelPolicy(true);
    }

    private static final Channel LISTENBRAINZ = new Channel(Service.LISTENBRAINZ, "ListenBrainz", "lb");
    private static final Channel LASTFM = new Channel(Service.LASTFM, "Last.fm", "lf");
    private static final Channel[] CHANNELS = {LISTENBRAINZ, LASTFM};
    private static boolean loaded; // guarded by LOCK

    /** The listen in progress. Only the STATE thread touches it. */
    private static Current current;
    private static boolean lastPlaying;
    private static ScheduledFuture<?> check;

    private Scrobbler() {
    }

    /** One service with its own queue and state. */
    private static final class Channel {
        final Service service;
        final String name;
        final String prefix;
        ListenQueue queue = new ListenQueue();
        /** The credential the service refused; nothing is sent while the credential stays this one. */
        volatile String refused = "";
        boolean retryScheduled;

        Channel(Service service, String name, String prefix) {
            this.service = service;
            this.name = name;
            this.prefix = prefix;
        }

        boolean enabled() {
            return preferences() != null && preferences().getBoolean(prefix + "_enabled", false);
        }

        String credential() {
            SharedPreferences preferences = preferences();
            return preferences == null ? "" : preferences.getString(prefix + "_credential", "");
        }

        /** Whether anything would be sent now: the switch is on, there is a credential and it was not refused. */
        boolean active() {
            String credential = credential();
            return enabled() && !credential.isEmpty() && !credential.equals(refused);
        }
    }

    private static final class Current {
        final String urn;
        final long startedAtSeconds;
        final HeardTime heard = new HeardTime();
        String artist;
        String title;
        boolean named;
        boolean submitted;

        Current(String urn, long startedAtSeconds) {
            this.urn = urn;
            this.startedAtSeconds = startedAtSeconds;
        }
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    // region Hooks

    /**
     * A new listen begins (also when the same track comes again). Called by {@link ListeningStats} on the player's
     * thread: only posts.
     *
     * @param lengthMs The length of the track, 0 if it is not known.
     */
    public static void onTrackStarted(String urn, long lengthMs) {
        long now = System.currentTimeMillis();
        STATE.execute(() -> guarded(() -> trackStarted(urn, lengthMs, 0, 0, now)));
    }

    /** The player started or stopped playing (ready and wanted). Called on the player's thread: only posts. */
    public static void onPlayingChanged(boolean playing) {
        long now = System.currentTimeMillis();
        STATE.execute(() -> guarded(() -> playingChanged(playing, now)));
    }

    /**
     * The player took a track up again at a position after the start (the app was closed and opened mid-track, or play
     * came after a pause). Does nothing while this track's listen is in progress; otherwise it begins one that already has
     * {@code heardMs} behind it. Called on the player's thread: only posts.
     *
     * @param heardMs What was heard of the listen before, as the statistics kept it.
     * @param startMs When the listen began, as the statistics kept it; 0 if not known.
     */
    public static void onTrackResumed(String urn, long lengthMs, long heardMs, long startMs) {
        long now = System.currentTimeMillis();
        STATE.execute(() -> guarded(() -> {
            if (current != null && current.urn.equals(urn)) return;
            trackStarted(urn, lengthMs, heardMs, startMs, now);
        }));
    }

    // endregion

    // region State thread

    private static void guarded(Runnable task) {
        try {
            task.run();
        } catch (Throwable ex) {
            Logger.printException(() -> "Scrobbling failed", ex);
        }
    }

    private static void trackStarted(String urn, long lengthMs, long heardMs, long startMs, long now) {
        loadQueues();
        cancelCheck();
        current = null;
        if (!LISTENBRAINZ.active() && !LASTFM.active()) return;
        // A listen taken up again began when the statistics say, else that long ago.
        long startedAt = startMs > 0 ? startMs / 1000 : (now - heardMs) / 1000;
        Current started = new Current(urn, startedAt);
        // After a restart nothing in memory says the listen was counted before the pause: the mark kept with the
        // scrobbler does, or it would be counted a second time, with another start time.
        if (heardMs > 0 && CountedListen.matches(countedMark(), urn, startedAt)) started.submitted = true;
        started.heard.begin(lengthMs, heardMs);
        started.heard.onPlaying(lastPlaying, now);
        current = started;
        NET.execute(() -> guarded(() -> resolveNames(started, 0)));
        // The queues may hold what an earlier session could not send.
        NET.execute(() -> guarded(Scrobbler::flushAll));
    }

    private static void playingChanged(boolean playing, long now) {
        lastPlaying = playing;
        Current listen = current;
        if (listen == null) return;
        listen.heard.onPlaying(playing, now);
        scheduleCheck(listen, now);
    }

    private static void namesFound(Current listen, String[] names) {
        if (current != listen) return;
        if (names == null || names.length < 2 || names[0] == null || names[0].trim().isEmpty()
                || names[1] == null || names[1].trim().isEmpty()) {
            // A listen needs both an artist and a title; a stub or a file without tags is of no use to a service.
            Logger.printInfo(() -> "Scrobbling: no artist and title for " + listen.urn);
            return;
        }
        listen.artist = names[0].trim();
        listen.title = names[1].trim();
        listen.named = true;
        ScrobbleListen now = new ScrobbleListen(listen.artist, listen.title, 0, listen.heard.durationMs());
        NET.execute(() -> guarded(() -> sendPlayingNow(now)));
        scheduleCheck(listen, System.currentTimeMillis());
    }

    private static void cancelCheck() {
        if (check != null) check.cancel(false);
        check = null;
    }

    /** Plans the moment the listen has been heard enough, or counts it at once when it already has. */
    private static void scheduleCheck(Current listen, long now) {
        cancelCheck();
        if (current != listen || listen.submitted || !listen.named) return;
        long remaining = listen.heard.remainingMs(now);
        if (remaining < 0) return; // too short to ever count
        if (remaining == 0) {
            submitListen(listen);
        } else if (listen.heard.isPlaying()) {
            check = STATE.schedule(() -> guarded(() -> scheduleCheck(listen, System.currentTimeMillis())),
                    remaining + 20, TimeUnit.MILLISECONDS);
        }
        // Paused: the next start of playing plans it again.
    }

    private static String countedMark() {
        SharedPreferences preferences = preferences();
        return preferences == null ? null : preferences.getString(COUNTED, null);
    }

    private static void submitListen(Current listen) {
        listen.submitted = true;
        SharedPreferences preferences = preferences();
        if (preferences != null) {
            preferences.edit().putString(COUNTED, CountedListen.mark(listen.urn, listen.startedAtSeconds)).apply();
        }
        ScrobbleListen finished = new ScrobbleListen(listen.artist, listen.title, listen.startedAtSeconds, listen.heard.durationMs());
        synchronized (LOCK) {
            for (Channel channel : CHANNELS) {
                if (!channel.active()) continue;
                channel.queue.add(finished, ListenQueue.ownerOf(channel.credential()));
                saveQueue(channel);
            }
        }
        NET.execute(() -> guarded(Scrobbler::flushAll));
    }

    // endregion

    // region Network thread

    private static void resolveNames(Current listen, int tried) {
        ListeningStats.NameLookup found = ListeningStats.lookupNames(listen.urn);
        STATE.execute(() -> guarded(() -> {
            if (current == listen && shouldAskAgain(found.names, found.unresolved, tried)) {
                // The server did not answer (no network, a busy API): the listen waits for its names, it is not lost.
                STATE.schedule(() -> guarded(() -> {
                    if (current == listen) NET.execute(() -> guarded(() -> resolveNames(listen, tried + 1)));
                }), RETRY_AFTER_SECONDS, TimeUnit.SECONDS);
                return;
            }
            namesFound(listen, found.names);
        }));
    }

    /** Whether a failed lookup of the names is asked again: only a lookup that never got an answer, and only a few times. */
    static boolean shouldAskAgain(String[] names, boolean unresolved, int tried) {
        return names == null && unresolved && tried + 1 < NAME_TRIES;
    }

    private static void sendPlayingNow(ScrobbleListen now) {
        for (Channel channel : CHANNELS) {
            String credential;
            synchronized (LOCK) {
                if (!channel.active()) continue;
                credential = channel.credential();
            }
            ListenOutcome outcome = attempt(channel, credential, now, false);
            if (outcome == ListenOutcome.REJECTED) rejected(channel, credential);
        }
    }

    private static ListenOutcome attempt(Channel channel, String credential, ScrobbleListen listen, boolean finished) {
        try {
            if (channel.service == Service.LISTENBRAINZ) {
                if (finished) ListenBrainzClient.submit(credential, listen);
                else ListenBrainzClient.playingNow(credential, listen);
            } else {
                if (finished) LastFmClient.submit(credential, listen);
                else LastFmClient.playingNow(credential, listen);
            }
            return ListenOutcome.DONE;
        } catch (ScrobbleException ex) {
            Logger.printInfo(() -> "Scrobbling to " + channel.name + ": " + ex.getMessage());
            return channel.service == Service.LISTENBRAINZ ? ListenBrainzClient.classify(ex.code) : LastFmClient.classify(ex.code);
        } catch (RuntimeException ex) {
            // A listen that makes the client throw is forgotten, like one the service calls invalid.
            Logger.printException(() -> "Scrobbling to " + channel.name + " failed", ex);
            return ListenOutcome.DROP;
        }
    }

    private static void flushAll() {
        loadQueues();
        for (Channel channel : CHANNELS) flush(channel);
    }

    private static void flush(Channel channel) {
        while (true) {
            ScrobbleListen front;
            String credential;
            synchronized (LOCK) {
                if (!channel.active()) return;
                credential = channel.credential();
                boolean changed = false;
                if (channel.service == Service.LASTFM) {
                    changed = channel.queue.dropOlderThan(System.currentTimeMillis() / 1000 - LAST_FM_OLDEST_SECONDS);
                }
                // Made under another account: not sent to this one's profile.
                changed |= channel.queue.keepOnlyFor(ListenQueue.ownerOf(credential));
                // Written only when it changed: this runs at every track start, with nothing waiting most of the time.
                if (changed) saveQueue(channel);
                front = channel.queue.peek();
                if (front == null) return;
            }
            ListenOutcome outcome = attempt(channel, credential, front, true);
            switch (outcome) {
                case DONE:
                case DROP:
                    synchronized (LOCK) {
                        channel.queue.removeFront(front);
                        saveQueue(channel);
                    }
                    break;
                case RETRY:
                    scheduleRetry(channel);
                    return;
                case REJECTED:
                default:
                    rejected(channel, credential);
                    return;
            }
        }
    }

    private static void scheduleRetry(Channel channel) {
        synchronized (LOCK) {
            if (channel.retryScheduled) return;
            channel.retryScheduled = true;
        }
        STATE.schedule(() -> {
            synchronized (LOCK) {
                channel.retryScheduled = false;
            }
            NET.execute(() -> guarded(Scrobbler::flushAll));
        }, RETRY_AFTER_SECONDS, TimeUnit.SECONDS);
    }

    private static void rejected(Channel channel, String usedCredential) {
        synchronized (LOCK) {
            if (!usedCredential.equals(channel.credential())) return; // the credential changed meanwhile: the new one is not refused
            if (usedCredential.equals(channel.refused)) return; // told once already
            channel.refused = usedCredential;
            channel.queue.clear();
            saveQueue(channel);
        }
        String note = channel.service == Service.LISTENBRAINZ
                ? Lang.text("ListenBrainz не принял токен: проверьте его в настройках",
                "ListenBrainz did not accept the token: check it in the settings")
                : Lang.text("Last.fm не принял подключение: подключитесь заново в настройках",
                "Last.fm did not accept the connection: connect again in the settings");
        Utils.runOnMainThread(() -> {
            Context context = Utils.getContext();
            if (context != null) Toast.makeText(context, note, Toast.LENGTH_LONG).show();
        });
    }

    // endregion

    // region Storage

    /** Reads the queues kept in the preferences, once. */
    private static void loadQueues() {
        synchronized (LOCK) {
            if (loaded) return;
            SharedPreferences preferences = preferences();
            if (preferences == null) return;
            loaded = true;
            for (Channel channel : CHANNELS) {
                channel.queue = ListenQueue.fromJson(preferences.getString(channel.prefix + "_queue", ""));
            }
        }
    }

    /** Called with LOCK held. */
    private static void saveQueue(Channel channel) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        if (channel.queue.isEmpty()) editor.remove(channel.prefix + "_queue");
        else editor.putString(channel.prefix + "_queue", channel.queue.toJson().toString());
        editor.apply();
    }

    // endregion

    // region Settings (called by the settings screen)

    private static Channel channel(Service service) {
        return service == Service.LISTENBRAINZ ? LISTENBRAINZ : LASTFM;
    }

    /** Whether the switch of the service is on. Off by default. */
    public static boolean isEnabled(Service service) {
        return channel(service).enabled();
    }

    public static void setEnabled(Service service, boolean enabled) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putBoolean(channel(service).prefix + "_enabled", enabled).apply();
        settingsChanged();
    }

    /** Whether an account is connected to the service. */
    public static boolean isConnected(Service service) {
        return !channel(service).credential().isEmpty();
    }

    /** The name of the connected account, "" when none. */
    public static String user(Service service) {
        SharedPreferences preferences = preferences();
        return preferences == null ? "" : preferences.getString(channel(service).prefix + "_user", "");
    }

    /** How many listens wait to be sent to the service. */
    public static int pending(Service service) {
        synchronized (LOCK) {
            Channel channel = channel(service);
            if (!loaded) {
                SharedPreferences preferences = preferences();
                return preferences == null ? 0 : ListenQueue.fromJson(preferences.getString(channel.prefix + "_queue", "")).size();
            }
            return channel.queue.size();
        }
    }

    /** Whether this build can talk to Last.fm: it needs the API key and the shared secret of the owner's account. */
    public static boolean isLastFmAvailable() {
        return LastFmClient.configured();
    }

    /** Forgets the connection and what waits to be sent to it. */
    public static void disconnect(Service service) {
        Channel channel = channel(service);
        SharedPreferences preferences = preferences();
        if (preferences != null) {
            preferences.edit().remove(channel.prefix + "_credential").remove(channel.prefix + "_user").apply();
        }
        settingsChanged();
    }

    /**
     * Checks a ListenBrainz token with the service and keeps it when it is valid. Blocks: not on the main thread.
     *
     * @return {@link Status#OK} with the user name, {@link Status#INVALID} or {@link Status#OFFLINE}.
     */
    public static Check saveListenBrainzToken(String token) {
        String clean = token == null ? "" : token.trim();
        // A character that cannot go in a header makes the request fail in the connection, with the token in its text:
        // refused here, before any request.
        if (!ListenBrainzClient.isTokenText(clean)) return new Check(Status.INVALID, "", "");
        try {
            String user = ListenBrainzClient.validate(clean);
            keepCredential(LISTENBRAINZ, clean, user);
            return new Check(Status.OK, user, "");
        } catch (ScrobbleException ex) {
            return new Check(ex.code == 401 || ex.code == 403 ? Status.INVALID : Status.OFFLINE, "", "");
        }
    }

    /**
     * Step 1 of the Last.fm approval: asks for a token and gives the address of the page where the user presses Allow.
     * Blocks: not on the main thread.
     */
    public static Check lastFmBegin() {
        if (!LastFmClient.configured()) return new Check(Status.NOT_SET_UP, "", "");
        try {
            String token = LastFmClient.getToken();
            return new Check(Status.OK, LastFmClient.authUrl(token), token);
        } catch (ScrobbleException ex) {
            return new Check(connectStatus(ex.code), "", "");
        }
    }

    /** What a failed step of the Last.fm connection means: a service or a network that is not there now, or a refusal. */
    static Status connectStatus(int code) {
        return LastFmClient.classify(code) == ListenOutcome.RETRY ? Status.OFFLINE : Status.INVALID;
    }

    /**
     * Step 3 of the approval, after the user pressed Allow: turns the token into a session and keeps it. Blocks.
     *
     * @return {@link Status#OK} with the user name, {@link Status#NOT_APPROVED} when the page was not answered yet,
     * {@link Status#INVALID} when the token is dead, {@link Status#OFFLINE}.
     */
    public static Check lastFmFinish(String token) {
        try {
            LastFmClient.Session session = LastFmClient.getSession(token);
            keepCredential(LASTFM, session.key, session.user);
            return new Check(Status.OK, session.user, "");
        } catch (ScrobbleException ex) {
            if (ex.code == LastFmClient.ERROR_BASE + 14) return new Check(Status.NOT_APPROVED, "", "");
            return new Check(connectStatus(ex.code), "", "");
        }
    }

    private static void keepCredential(Channel channel, String credential, String user) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        preferences.edit().putString(channel.prefix + "_credential", credential).putString(channel.prefix + "_user", user).apply();
        // The service just accepted this credential: an earlier refusal of the same text (a fault, a brief revoke) is over.
        synchronized (LOCK) {
            channel.refused = "";
        }
        // A fresh connection is also an intention to scrobble: the service stays as the user left its switch.
        settingsChanged();
    }

    /** A switch or a credential changed: a refused credential that was replaced gets its chance, a service turned off forgets its queue. */
    public static void settingsChanged() {
        NET.execute(() -> guarded(() -> {
            loadQueues();
            synchronized (LOCK) {
                for (Channel channel : CHANNELS) {
                    if (!channel.credential().equals(channel.refused)) channel.refused = "";
                    if (!channel.enabled() || channel.credential().isEmpty()) {
                        channel.queue.clear();
                        saveQueue(channel);
                    }
                }
            }
            flushAll();
        }));
    }

    // endregion
}
