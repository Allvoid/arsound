package app.revanced.extension.soundcloud.scrobble;

/** What the program does with the answer of a service. */
enum ListenOutcome {
    /** Accepted. */
    DONE,
    /** The service or the network is not there now: keep the listen, ask again later. */
    RETRY,
    /** The credential (token, session) is refused: stop until it changes. */
    REJECTED,
    /** This listen cannot be accepted (the service says the data is wrong): forget it. */
    DROP
}
