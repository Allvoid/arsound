package app.arsound.patches.soundcloud.recommendations

import app.arsound.patches.soundcloud.misc.manifest.addApplicationComponent
import app.arsound.patches.soundcloud.misc.manifest.declaresApplicationComponent
import app.revanced.patcher.patch.resourcePatch

private const val JOB_SERVICE = "app.revanced.extension.soundcloud.recommendations.ForYouJob"

/** For you: Lets Android's job scheduler start the midnight update of the "For you" playlist. Part of the "Arsound" patch, not shown on its own. */
val forYouPatch = resourcePatch {
    compatibleWith("com.soundcloud.android")

    apply {
        document("AndroidManifest.xml").use { document ->
            if (!document.declaresApplicationComponent("service", JOB_SERVICE)) {
                document.addApplicationComponent(
                    "service",
                    "android:name" to JOB_SERVICE,
                    "android:permission" to "android.permission.BIND_JOB_SERVICE",
                    "android:exported" to "false",
                )
            }
        }
    }
}
