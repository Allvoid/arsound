package app.revanced.extension.soundcloud.local;

/** What SoundCloud's API answers when asked whether a track still exists, from the HTTP code. */
enum TrackAnswer {
    /** The track exists (it may be private to somebody else). */
    EXISTS,
    /** The track was deleted. */
    DELETED,
    /** The API itself failed (no valid response, an expired token, a rate limit, a server error): the answer says nothing about the track. */
    API_FAILED,
    /** Anything else. */
    UNCLEAR;

    /** @param code The HTTP code as text, "-1" when there was no valid response. */
    static TrackAnswer of(String code) {
        if ("404".equals(code)) return DELETED;
        if (code.startsWith("2") || "403".equals(code)) return EXISTS;
        if ("-1".equals(code) || "401".equals(code) || "429".equals(code) || code.startsWith("5")) return API_FAILED;
        return UNCLEAR;
    }
}
