package app.arsound.patches.soundcloud.debug

import app.arsound.patches.soundcloud.misc.extension.sharedExtensionPatch
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/debug/PlaybackTimeline;"

private val BytecodePatchContext.applicationOnCreateMethod by gettingFirstMethodDeclaratively {
    name("onCreate")
    definingClass("Lcom/soundcloud/android/app/RealSoundCloudApplication;")
    returnType("V")
}

/**
 * Playback timeline (personal build only): Catches SoundCloud's own playback log and writes it to a file
 * kept for two days, with the delay of every track start and its cause.
 * Part of the "Arsound" patch, not shown on its own.
 */
val playbackTimelinePatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        applicationOnCreateMethod.addInstructions(
            0,
            "invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->plant()V",
        )
    }
}
