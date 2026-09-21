package app.arsound.patches.soundcloud.misc.permissions

import app.revanced.patcher.patch.resourcePatch
import app.arsound.util.asSequence
import org.w3c.dom.Element

/**
 * Remove phone permission: SoundCloud asks for "Phone" to pause music during calls, which audio focus already does.
 * Part of the "Arsound" patch, not shown on its own.
 */
val removePhonePermissionPatch = resourcePatch {
    compatibleWith("com.soundcloud.android")

    apply {
        document("AndroidManifest.xml").use { document ->
            document.getElementsByTagName("uses-permission").asSequence()
                .map { it as Element }
                .filter { it.getAttribute("android:name") == "android.permission.READ_PHONE_STATE" }
                .toList()
                .forEach { it.parentNode.removeChild(it) }
        }
    }
}
