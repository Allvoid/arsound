package app.revanced.patches.soundcloud.local

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/local/LocalMusic;"

/**
 * The single entry point that turns a list of tracks into a play queue and starts it.
 */
private val BytecodePatchContext.playbackInitiatorConstructorMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/playback/session/PlaybackInitiator;")
}

@Suppress("unused")
val localMusicPatch = bytecodePatch(
    name = "Local music",
    description = "Adds importing audio files from the phone and playing them in the SoundCloud player.",
) {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        playbackInitiatorConstructorMethod.apply {
            addInstruction(
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->setPlaybackInitiator(Ljava/lang/Object;)V",
            )
        }
    }
}
