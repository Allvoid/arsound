package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.hooks.requireResolved
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/player/PlaybackEvents;"

private const val PROGRESS_EVENT = "Lcom/soundcloud/android/playback/core/event/ProgressChangeEvent;"

/** Hands the player's position report on to the session: about twice a second while a track plays. */
private val BytecodePatchContext.progressChangedMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/players/playback/local/LocalPlayback;")
    name("i")
    returnType("V")
    parameterTypes(PROGRESS_EVENT)
}

/** Sets the volume of the player; the extension calls it by name for the fades. */
private val BytecodePatchContext.playerSetVolumeMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/exoplayer/BaseExoPlayer;")
    name("setVolume")
    returnType("V")
    parameterTypes("F")
}

/**
 * Track transitions: the end of a track fades out and the next one fades in (off by default), and the next streamed
 * track of the queue is loaded whole while the current one plays. Part of the "Arsound" patch, not shown on its own.
 */
val trackTransitionsPatch = bytecodePatch {
    dependsOn(settingsPatch, playModePatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        // Looked up only so that a renamed method fails the patch, not the fades in the app.
        requireResolved(playerSetVolumeMethod)

        playerPlayMethod.addInstruction(0, "invoke-static { p0, p1 }, $EXTENSION_CLASS_DESCRIPTOR->onPlay(Ljava/lang/Object;Ljava/lang/Object;)V")
        // b is the position and c the length, in milliseconds: SoundCloud's own fade of previews reads them so.
        // The method has 14 locals, still unset at its start.
        progressChangedMethod.addInstructions(
            0,
            """
                iget-wide v0, p1, $PROGRESS_EVENT->b:J
                iget-wide v2, p1, $PROGRESS_EVENT->c:J
                invoke-static/range { v0 .. v3 }, $EXTENSION_CLASS_DESCRIPTOR->onProgress(JJ)V
            """,
        )
    }
}
