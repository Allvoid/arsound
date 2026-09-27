package app.arsound.patches.soundcloud.player

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.patch.bytecodePatch
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/player/AudioEqualizer;"

/**
 * The runnable of ExoPlayerImpl that asks Android for an audio session in the background
 * ({@code androidx.media3.exoplayer.i.run()}).
 */
private const val SESSION_RUNNABLE = "Landroidx/media3/exoplayer/i;"

/** Equalizer: attaches Android's equalizer to the player's audio session. Part of the "Arsound" patch, not shown on its own. */
val equalizerPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        firstClassDef(SESSION_RUNNABLE).methods.first { it.name == "run" }.apply {
            val generateIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.name == "generateAudioSessionId"
            }
            val sessionRegister = getInstruction<OneRegisterInstruction>(generateIndex + 1).registerA
            addInstruction(
                generateIndex + 2,
                "invoke-static { v$sessionRegister }, $EXTENSION_CLASS_DESCRIPTOR->onAudioSession(I)V",
            )
        }
    }
}
