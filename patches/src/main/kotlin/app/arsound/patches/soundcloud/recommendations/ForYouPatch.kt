package app.arsound.patches.soundcloud.recommendations

import app.arsound.util.asSequence
import app.revanced.patcher.patch.resourcePatch
import org.w3c.dom.Element

/** For you: Lets Android's job scheduler start the midnight update of the "For you" playlist. Part of the "Arsound" patch, not shown on its own. */
val forYouPatch = resourcePatch {
    compatibleWith("com.soundcloud.android")

    apply {
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as Element
            val name = "app.revanced.extension.soundcloud.recommendations.ForYouJob"
            val declared = application.getElementsByTagName("service").asSequence()
                .any { (it as Element).getAttribute("android:name") == name }
            if (!declared) {
                application.appendChild(
                    document.createElement("service").apply {
                        setAttribute("android:name", name)
                        setAttribute("android:permission", "android.permission.BIND_JOB_SERVICE")
                        setAttribute("android:exported", "false")
                    },
                )
            }
        }
    }
}
