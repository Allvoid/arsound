package app.arsound.patches.soundcloud.debug

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.applicationOnCreateMethod
import app.arsound.patches.soundcloud.misc.extension.sharedExtensionPatch
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/debug/PlaybackTimeline;"

/**
 * Playback timeline (off by default, switched in the developer settings): Catches SoundCloud's own playback log and writes it to a file
 * kept for two days, with the delay of every track start and its cause.
 * Part of the "Arsound" patch, not shown on its own.
 */
val playbackTimelinePatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        applicationOnCreateMethod.addInstructions(
            0,
            "invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->plant()V",
        )
    }
}
