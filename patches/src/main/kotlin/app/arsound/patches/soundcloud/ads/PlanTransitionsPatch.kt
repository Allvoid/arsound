package app.arsound.patches.soundcloud.ads

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType

/** Starts the listener of plan changes: it opens the "Don't stop the music" screen and then restarts the app. */
private val BytecodePatchContext.planChangeListenerMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/configuration/DefaultConfigurationUpdatesLifecycleObserver;")
    returnType("V")
    parameterTypes()
}

/**
 * Removes the plan transition screens ("Don't stop the music", the welcome screen of a new plan) and the app restart
 * that follows them. The listener never starts, so a plan change reported by the server shows nothing.
 * Part of the "Arsound: без лишнего" group, not shown on its own.
 */
internal val hidePlanTransitionsPatch = bytecodePatch {
    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        planChangeListenerMethod.addInstructions(0, "return-void")
    }
}
