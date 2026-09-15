package app.revanced.patches.soundcloud.ads

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.revanced.patches.soundcloud.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/ads/PlaybackAdPatch;"

/**
 * The controller receives every reason for requesting a queue-start or mid-queue advertisement.
 * Guarding this point avoids both the HTTP request and insertion of an ad item into the play queue.
 */
private val BytecodePatchContext.playbackAdRequestMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ads/promoted/PromotedPlayerAdsController;")
    returnType("V")
    parameterTypes("Lcom/soundcloud/android/ads/player/PlayerAdsController\$AdFetchReason;")
}

@Suppress("unused")
val playbackAdsPatch = bytecodePatch(
    name = "Control playback advertisements",
    description = "Adds an option to prevent SoundCloud from requesting and queuing audio and video advertisements.",
) {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        playbackAdRequestMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->blockAdRequest()Z
                move-result v0
                if-eqz v0, :request
                return-void
            """,
            ExternalLabel("request", playbackAdRequestMethod.getInstruction(0)),
        )
    }
}
