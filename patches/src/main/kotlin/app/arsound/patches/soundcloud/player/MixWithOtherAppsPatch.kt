package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.hooks.GuardExit
import app.arsound.patches.soundcloud.misc.hooks.insertGuard
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.method
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.arsound.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/player/MixWithOtherApps;"

/**
 * Asks Android for audio focus before the player starts. Every start and resume of SoundCloud's own player goes
 * through it (PlaybackManager plays only if it returns true).
 */
private val BytecodePatchContext.requestAudioFocusMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/players/playback/local/LocalPlayback;")
    name("n")
    returnType("Z")
    parameterTypes()
    instructions(
        method { name == "requestAudioFocus" },
    )
}

/**
 * Play along with other apps: the player starts without taking the audio focus, so a video, a voice message or
 * another player does not pause SoundCloud. Calls still pause it. Part of the "Arsound" patch, not shown on its own.
 */
val mixWithOtherAppsPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        requestAudioFocusMethod.apply {
            // The focus request object of the player; the extension reads its listener and Android request from it.
            val requestField = getInstruction<ReferenceInstruction>(
                indexOfFirstInstructionOrThrow {
                    opcode == Opcode.IGET_OBJECT && fieldReference?.type == "Landroidx/media/AudioFocusRequestCompat;"
                },
            ).reference
            insertGuard(
                "invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->skipAudioFocus(Ljava/lang/Object;)Z",
                GuardExit.ReturnTrue,
                prelude = "iget-object v0, p0, $requestField",
            )
        }
    }
}
