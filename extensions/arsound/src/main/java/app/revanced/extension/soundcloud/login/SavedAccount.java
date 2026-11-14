package app.revanced.extension.soundcloud.login;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A SoundCloud account kept for switching: what SoundCloud itself keeps of a signed-in account in Android's account
 * manager (the account's name, the user id, the two tokens and their scope and lifetime, the e-mail), so the account
 * can be put back there later without signing in again.
 * <p>
 * The tokens are as secret as SoundCloud's own copy: they stay in the app's private files and never travel in the
 * settings backup ({@code settings.TransferPlan} knows nothing of {@link AccountVault#PREFERENCES}).
 */
final class SavedAccount {
    /** The name of the account in Android's account manager: SoundCloud makes it "&lt;username&gt;-&lt;user id&gt;". */
    final String name;
    final String userId;
    final String accessToken;
    final String refreshToken;
    final String scope;
    final String expiresIn;
    final String email;
    /** When the account was last seen signed in, in milliseconds since 1970: the list is in this order, newest first. */
    final long seenAt;

    SavedAccount(String name, String userId, String accessToken, String refreshToken, String scope, String expiresIn,
                 String email, long seenAt) {
        this.name = name;
        this.userId = userId;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.scope = scope;
        this.expiresIn = expiresIn;
        this.email = email;
        this.seenAt = seenAt;
    }

    /** The name to show: the username, without the user id SoundCloud adds to the account's name. */
    String shownName() {
        return shownName(name);
    }

    static String shownName(String accountName) {
        if (accountName == null) return "";
        String name = accountName;
        int at = name.indexOf('@');
        if (at > 0) name = name.substring(0, at);
        String shown = name.replaceFirst("-\\d+$", "");
        return shown.isEmpty() ? name : shown;
    }

    /** Whether the account can be signed in with: SoundCloud needs at least one of the two tokens and the user id. */
    boolean usable() {
        return name != null && !name.isEmpty() && userId != null && !userId.isEmpty()
                && (notEmpty(accessToken) || notEmpty(refreshToken));
    }

    /** Whether the other copy is of the same account with the same tokens and data, whenever it was made. */
    boolean sameSession(SavedAccount other) {
        return other != null && java.util.Objects.equals(name, other.name) && java.util.Objects.equals(userId, other.userId)
                && java.util.Objects.equals(accessToken, other.accessToken)
                && java.util.Objects.equals(refreshToken, other.refreshToken)
                && java.util.Objects.equals(scope, other.scope) && java.util.Objects.equals(expiresIn, other.expiresIn)
                && java.util.Objects.equals(email, other.email);
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("name", name);
        json.put("userId", userId);
        putIfSet(json, "accessToken", accessToken);
        putIfSet(json, "refreshToken", refreshToken);
        putIfSet(json, "scope", scope);
        putIfSet(json, "expiresIn", expiresIn);
        putIfSet(json, "email", email);
        json.put("seenAt", seenAt);
        return json;
    }

    private static void putIfSet(JSONObject json, String key, String value) throws JSONException {
        if (value != null) json.put(key, value);
    }

    /** Reads one entry back; null if it is not one (damaged by hand or by an older format). */
    static SavedAccount fromJson(JSONObject json) {
        if (json == null) return null;
        SavedAccount account = new SavedAccount(
                json.optString("name", null),
                json.optString("userId", null),
                json.optString("accessToken", null),
                json.optString("refreshToken", null),
                json.optString("scope", null),
                json.optString("expiresIn", null),
                json.optString("email", null),
                json.optLong("seenAt", 0));
        return account.usable() ? account : null;
    }
}
