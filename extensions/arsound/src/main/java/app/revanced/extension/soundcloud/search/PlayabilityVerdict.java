package app.revanced.extension.soundcloud.search;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What YouTube's refusal of a track means, from the playability status and the reason it gives, for a visitor and for
 * a signed-in account. Each verdict knows the exception it stands for. Pure, no Android.
 */
enum PlayabilityVerdict {
    PLAYABLE {
        @Override
        IOException failure(String status, String reason) {
            return null;
        }
    },
    /** A visitor was taken for a bot. */
    VISITOR_BOT {
        @Override
        IOException failure(String status, String reason) {
            return new YouTubeAccount.BotCheckException("YouTube refused the visitor as a bot: " + reason);
        }
    },
    /** Age-restricted: a visitor never gets it. */
    VISITOR_NEEDS_ACCOUNT {
        @Override
        IOException failure(String status, String reason) {
            return new YouTubeAccount.SignInRequiredException();
        }
    },
    VISITOR_REFUSED {
        @Override
        IOException failure(String status, String reason) {
            return new IOException("YouTube refused the visitor request: " + status + " " + reason);
        }
    },
    /**
     * A signed-in account that has not confirmed its age is asked to "sign in to confirm your age": its session is
     * fine, and signing in again would change nothing.
     */
    ACCOUNT_NEEDS_AGE_CONFIRMATION {
        @Override
        IOException failure(String status, String reason) {
            return new IOException("YouTube asks this account to confirm its age: " + reason);
        }
    },
    /**
     * A session that Google revoked (signed out on all devices, new password) answers like a visitor with
     * LOGIN_REQUIRED. A bot check asks for it too, but says nothing about the session, so it is kept.
     */
    SESSION_REVOKED {
        @Override
        IOException failure(String status, String reason) {
            return new YouTubeAccount.SignInRequiredException("YouTube no longer accepts the saved session: sign in again");
        }

        @Override
        boolean dropsSession() {
            return true;
        }
    },
    ACCOUNT_BOT {
        @Override
        IOException failure(String status, String reason) {
            return new YouTubeAccount.BotCheckException("YouTube refused the account as a bot: " + reason);
        }
    },
    ACCOUNT_REFUSED {
        @Override
        IOException failure(String status, String reason) {
            return new IOException("YouTube refused the signed-in request: " + status + " " + reason);
        }
    };

    /** Whole words of a refusal reason: "bot" is not in "both", "age" is not in "page" or "message". */
    private static final Pattern BOT = Pattern.compile("\\bbots?\\b");
    private static final Pattern AGE = Pattern.compile("\\bage\\b");

    /** The exception for this verdict, or null if the track is playable. */
    abstract IOException failure(String status, String reason);

    /** Whether the saved session is dead: else the sign-in page would find its cookies and save the same session again. */
    boolean dropsSession() {
        return false;
    }

    /**
     * @param signedIn Whether the request carried the cookies of an account.
     * @param status   The playability status, such as OK or LOGIN_REQUIRED; empty if the answer had none.
     * @param reason   The reason in words; empty if there was none.
     */
    static PlayabilityVerdict of(boolean signedIn, String status, String reason) {
        if ("OK".equalsIgnoreCase(status)) return PLAYABLE;
        boolean loginRequired = "LOGIN_REQUIRED".equalsIgnoreCase(status);
        String lowerReason = reason.toLowerCase(Locale.ROOT);
        boolean bot = BOT.matcher(lowerReason).find();
        if (!signedIn) {
            if (bot) return VISITOR_BOT;
            return loginRequired ? VISITOR_NEEDS_ACCOUNT : VISITOR_REFUSED;
        }
        if (loginRequired && AGE.matcher(lowerReason).find()) return ACCOUNT_NEEDS_AGE_CONFIRMATION;
        if (loginRequired && !bot) return SESSION_REVOKED;
        return bot ? ACCOUNT_BOT : ACCOUNT_REFUSED;
    }
}
