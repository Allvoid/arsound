package app.revanced.extension.soundcloud.debug;

import java.util.regex.Pattern;

/**
 * Hides what lets someone sign in as the user, before a line goes into the file that is sent to the developer.
 * <p>
 * The sign-in page returns to the app through an address that carries the authorization code
 * ({@code sc://auth?code=...}); tokens show up in request logs as parameters, JSON fields and headers. Everything
 * else in the line is kept: an error without its context is useless.
 */
public final class SecretRedactor {
    static final String MASK = "<redacted>";

    private static final String SECRET_NAMES =
            "access_token|refresh_token|id_token|oauth_token|client_secret|code_verifier|password";

    /** The sign-in callback addresses: everything after the question mark (or the hash) is the code or the token. */
    private static final Pattern AUTH_CALLBACK = Pattern.compile(
            "((?:sc://auth|auth_mobile_callback)[^\\s?#\"'<>]*)[?#][^\\s\"'<>]*", Pattern.CASE_INSENSITIVE);
    /** {@code access_token=abc} in an address or a form. */
    private static final Pattern PARAMETER = Pattern.compile(
            "\\b((?:" + SECRET_NAMES + ")=)[^&\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    /** {@code "access_token":"abc"} in JSON. */
    private static final Pattern JSON_FIELD = Pattern.compile(
            "(\"(?:" + SECRET_NAMES + ")\"\\s*:\\s*\")[^\"]*", Pattern.CASE_INSENSITIVE);
    /** {@code Authorization: OAuth 2-123...}; a real token has a digit, which keeps ordinary words ("OAuth flow") out. */
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "\\b(Bearer|OAuth)\\s+(?=[A-Za-z0-9._~+/=-]*\\d)[A-Za-z0-9._~+/=-]{8,}", Pattern.CASE_INSENSITIVE);

    private SecretRedactor() {
    }

    public static String redact(String text) {
        String result = AUTH_CALLBACK.matcher(text).replaceAll("$1?" + MASK);
        result = PARAMETER.matcher(result).replaceAll("$1" + MASK);
        result = JSON_FIELD.matcher(result).replaceAll("$1" + MASK);
        return AUTHORIZATION.matcher(result).replaceAll("$1 " + MASK);
    }
}
