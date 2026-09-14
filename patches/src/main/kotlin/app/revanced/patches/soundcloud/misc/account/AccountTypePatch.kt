package app.revanced.patches.soundcloud.misc.account

import app.revanced.patcher.patch.resourcePatch
import app.revanced.util.asSequence
import org.w3c.dom.Element

private const val ORIGINAL_ACCOUNT_TYPE = "com.soundcloud.android.account"
private const val REVANCED_ACCOUNT_TYPE = "com.soundcloud.android.revanced.account"

@Suppress("unused")
val accountTypePatch = resourcePatch(
    name = "Change account type",
    description = "Uses a separate Android account type, so the app can sign in " +
        "while the original SoundCloud app is installed. Required with \"Change package name\".",
) {
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
        }
    }
}
