package app.arsound.patches.soundcloud.misc.minsdk

import app.arsound.util.getNode
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.patch.stringOption
import org.w3c.dom.Element

/**
 * Lower the minimum Android version: SoundCloud asks for Android 12L (API 32). This lets the app install on older
 * Android. Experimental and off by default: SoundCloud's code is built for 12L, and calls to newer system functions
 * can fail on an older Android.
 */
@Suppress("unused")
val lowerMinSdkPatch = resourcePatch(
    name = "Lower minimum Android version",
    description = "Experimental: lets SoundCloud install on Android older than 12L. Parts of the app may not work there.",
    use = false,
) {
    compatibleWith("com.soundcloud.android")

    val minSdk by stringOption(
        default = "26",
        name = "Min SDK",
        description = "The lowest Android API level the app installs on, for example 29 for Android 10.",
        required = true,
    ) { it!!.toIntOrNull()?.let { level -> level in 21..32 } == true }

    apply {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getNode("manifest") as Element
            val usesSdk = document.getElementsByTagName("uses-sdk").item(0) as Element?
                ?: document.createElement("uses-sdk").also { manifest.insertBefore(it, manifest.firstChild) }
            usesSdk.setAttribute("android:minSdkVersion", minSdk!!)
        }
    }
}
