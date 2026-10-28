package app.arsound.patches.soundcloud.upsell

import app.arsound.patches.soundcloud.misc.hooks.GuardExit
import app.arsound.patches.soundcloud.misc.hooks.insertGuard
import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch

private val BytecodePatchContext.showInAppMessageMethod by gettingFirstMethodDeclaratively("Failed to show in-app message") {
    definingClass("Lcom/soundcloud/android/moengage/DefaultMoEngageSdk;")
}

private val BytecodePatchContext.showNudgeMethod by gettingFirstMethodDeclaratively("Failed to show nudge") {
    definingClass("Lcom/soundcloud/android/moengage/DefaultMoEngageSdk;")
}

/** MoEngage in-app messages and nudges, the marketing popups shown on app start. */
internal val inAppMessagesPatch = bytecodePatch {
    apply {
        listOf(showInAppMessageMethod, showNudgeMethod).forEach { method ->
            method.insertGuard(HIDE_IN_APP_MESSAGES_CALL, GuardExit.ReturnVoid)
        }
    }
}
