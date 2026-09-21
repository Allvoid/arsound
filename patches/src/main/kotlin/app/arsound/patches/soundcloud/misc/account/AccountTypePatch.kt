package app.arsound.patches.soundcloud.misc.account

import app.revanced.patcher.patch.resourcePatch
import app.arsound.util.asSequence
import org.w3c.dom.Element

private const val ORIGINAL_ACCOUNT_TYPE = "com.soundcloud.android.account"
private const val REVANCED_ACCOUNT_TYPE = "com.soundcloud.android.revanced.account"
private const val ORIGINAL_AUTHORITY = "com.soundcloud.android.provider.ScContentProvider"
private const val REVANCED_AUTHORITY = "com.soundcloud.android.revanced.provider.ScContentProvider"

/** Change account type: Uses a separate Android account type, so the app can sign in while the original SoundCloud app is installed. Required with "Change package name". Part of the "Arsound" patch, not shown on its own. */
val accountTypePatch = resourcePatch {
    compatibleWith("com.soundcloud.android")

    apply {
        // The authenticator, the sync adapter and the app code all read the account type from this string.
        document("res/values/strings.xml").use { document ->
            document.getElementsByTagName("string").asSequence()
                .map { it as Element }
                .first { it.getAttribute("name") == "account_type" }
                .apply {
                    if (textContent == ORIGINAL_ACCOUNT_TYPE) textContent = REVANCED_ACCOUNT_TYPE
                }

            // The sync adapter reads its content authority from this string. "Change package name" renames the
            // provider authority in the manifest, so without this the periodic account sync never finds its provider.
            document.getElementsByTagName("string").asSequence()
                .map { it as Element }
                .firstOrNull { it.getAttribute("name") == "account_authority" }
                ?.apply {
                    if (textContent == ORIGINAL_AUTHORITY) textContent = REVANCED_AUTHORITY
                }
        }
    }
}
