/**
 * Scrobbling: the tracks listened to go to ListenBrainz and to Last.fm.
 *
 * <p>User view: a "Scrobbling" section in the settings. Each service is off by default. ListenBrainz needs a token
 * pasted from listenbrainz.org/settings; Last.fm is connected by pressing Allow on the last.fm page that opens in the
 * browser (the password is never seen, the account name is shown). A track counts after half of its length or four
 * minutes of real listening (and only if it is at least 30 seconds long); the track playing now is announced too. What
 * cannot be sent (no network) waits in a queue of at most 100 listens per service and is sent later. Same rule and
 * limits as Arsound Desktop.
 *
 * <p>Entry points (no patch of their own): {@code ListeningStats.onPlaybackItem} calls
 * {@code Scrobbler.onTrackStarted(urn, length)} and {@code ListeningStats.onPlayerState} calls
 * {@code Scrobbler.onPlayingChanged(playing)}; both only post to a worker thread. The artist and title come from
 * {@code ListeningStats.namesOf(urn)}.
 *
 * <p>Classes: {@code Scrobbler} (hooks, queues, threads, settings API), {@code HeardTime} (the half-or-four-minutes rule),
 * {@code ListenQueue} (the persisted queue), {@code ListenBrainzClient}, {@code LastFmClient} (signing, approval flow),
 * {@code WebCall} (HTTP), {@code ScrobbleKeys} (generated at build time).
 *
 * <p>Storage: the preferences file {@code arsound_scrobble} (switches, token, session key, queue); it is a credential file
 * of the settings transfer and never leaves the phone. Last.fm key and shared secret: {@code local/lastfm.properties}
 * ({@code api_key}, {@code shared_secret}) or the environment variables {@code ARSOUND_LASTFM_API_KEY} and
 * {@code ARSOUND_LASTFM_SHARED_SECRET}, written at build time into the generated {@code ScrobbleKeys}; without the
 * secret Last.fm is not offered.
 *
 * <p>Gotchas: nothing here may block the player thread or the main thread; the Last.fm signature is made on the raw
 * UTF-8 values and excludes {@code format}; Last.fm refuses listens older than two weeks.
 */
package app.revanced.extension.soundcloud.scrobble;
