package app.revanced.extension.soundcloud.scrobble;

import java.io.IOException;

/**
 * A call to a scrobbling service that did not succeed. {@code code} is an HTTP status, {@code 0} for a failure of the
 * network, or {@link LastFmClient#ERROR_BASE} plus the number of a Last.fm API error.
 */
final class ScrobbleException extends IOException {
    final int code;

    ScrobbleException(int code, String message) {
        super(message);
        this.code = code;
    }
}
