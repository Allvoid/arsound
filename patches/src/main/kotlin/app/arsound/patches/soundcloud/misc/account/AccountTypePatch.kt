package app.arsound.patches.soundcloud.misc.account

import app.revanced.patcher.patch.resourcePatch
import app.arsound.patches.all.misc.packagename.setOrGetFallbackPackageName
import app.arsound.util.asSequence
import org.w3c.dom.Element

private const val ORIGINAL_ACCOUNT_TYPE = "com.soundcloud.android.account"
private const val ORIGINAL_AUTHORITY = "com.soundcloud.android.provider.ScContentProvider"

/** Change account type: Uses a separate Android account type, so the app can sign in while the original SoundCloud app is installed. Required with "Change package name". Part of the "Arsound" patch, not shown on its own. */
val accountTypePatch = resourcePatch {
    compatibleWith("com.soundcloud.android")

    apply {
        // Named after the final package: "com.soundcloud.android.revanced.account" for the usual build, another one
        // for the build under a Chinese player's package, so both builds can be installed at once.
        val packageName = setOrGetFallbackPackageName("com.soundcloud.android.revanced")
        val accountType = "$packageName.account"
        val authority = "$packageName.provider.ScContentProvider"

        // The authenticator, the sync adapter and the app code all read the account type from this string.
        document("res/values/strings.xml").use { document ->
            document.getElementsByTagName("string").asSequence()
                .map { it as Element }
                .first { it.getAttribute("name") == "account_type" }
                .apply {
                    if (textContent == ORIGINAL_ACCOUNT_TYPE) textContent = accountType
                }

            // The sync adapter reads its content authority from this string. "Change package name" renames the
            // provider authority in the manifest, so without this the periodic account sync never finds its provider.
            document.getElementsByTagName("string").asSequence()
                .map { it as Element }
                .firstOrNull { it.getAttribute("name") == "account_authority" }
                ?.apply {
                    if (textContent == ORIGINAL_AUTHORITY) textContent = authority
                }
        }
    }
}
