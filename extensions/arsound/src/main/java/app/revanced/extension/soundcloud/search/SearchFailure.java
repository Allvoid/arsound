package app.revanced.extension.soundcloud.search;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;

import app.revanced.extension.soundcloud.network.IpCountry;
import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.shared.Toasts;

/**
 * Why a preview, a download or a search failed, read once from the chain of causes of the error. Each kind knows what
 * to tell the user or ask them; the kinds are listed in the order they win when an error has several causes.
 */
enum SearchFailure {
    /** YouTube took the network for a bot. */
    BOT_CHECK {
        @Override
        void report(Context context, String refusedText, String otherText) {
            // With an account, signing in again would not help: another network might.
            if (YouTubeAccount.isSignedIn()) {
                Toasts.showLong(context, botCheckText());
            } else {
                SignInOffer.offer(true);
            }
        }
    },
    /** An age-restricted track: YouTube gives it only to an account. */
    SIGN_IN_REQUIRED {
        @Override
        void report(Context context, String refusedText, String otherText) {
            SignInOffer.offer(false);
        }
    },
    /** The region guard stopped the request. */
    REGION_BLOCKED {
        @Override
        void report(Context context, String refusedText, String otherText) {
            Toasts.showLong(context, regionBlockedText());
        }
    },
    /** YouTube refused the file: the VPN seems to send requests from different addresses. */
    REFUSED {
        @Override
        void report(Context context, String refusedText, String otherText) {
            Toasts.showLong(context, refusedText);
        }
    },
    OTHER {
        @Override
        void report(Context context, String refusedText, String otherText) {
            Toasts.showLong(context, otherText);
        }
    };

    /**
     * Tells the user.
     *
     * @param refusedText What to say for {@link #REFUSED}; a preview has no such case and passes its general text.
     * @param otherText   What to say when the cause is none of the known ones.
     */
    abstract void report(Context context, String refusedText, String otherText);

    static SearchFailure of(Throwable error) {
        boolean botCheck = false;
        boolean signIn = false;
        boolean refused = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof YouTubeAccount.BotCheckException) botCheck = true;
            if (cause instanceof YouTubeAccount.SignInRequiredException) signIn = true;
            if (cause instanceof OtherSource.RefusedException) refused = true;
        }
        if (botCheck) return BOT_CHECK;
        if (signIn) return SIGN_IN_REQUIRED;
        if (isRegionBlock(error)) return REGION_BLOCKED;
        return refused ? REFUSED : OTHER;
    }

    static boolean isRegionBlock(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RegionGuard.BlockedException) return true;
        }
        return false;
    }

    private static String botCheckText() {
        return text("YouTube принимает запросы из этой сети за бота. Попробуйте другую сеть или другой сервер VPN.",
                "YouTube takes the requests of this network for a bot. Try another network or VPN server.");
    }

    /** Why the Arsound search is off: the country of the IP address, or that it is not checked yet. */
    static String regionBlockedText() {
        String country = RegionGuard.lastCountry();
        if (country == null) {
            return text("Страна IP не проверена — поиск Arsound ждёт проверки.",
                    "The IP country is not checked: the Arsound search waits for the check.");
        }
        return text(IpCountry.describeCapitalized(country, true) + " — поиск Arsound отключён.",
                IpCountry.describe(country, false) + ": the Arsound search is off.");
    }
}
