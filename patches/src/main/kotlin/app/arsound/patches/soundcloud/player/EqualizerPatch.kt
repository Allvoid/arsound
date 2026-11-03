package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
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

private const val SOFT_EQUALIZER = "Lapp/revanced/extension/soundcloud/player/SoftEqualizer;"

/** The chain of audio processors of the player's audio sink: the equalizer is put into it. */
private val BytecodePatchContext.audioProcessorChainMethod by gettingFirstMethodDeclaratively {
    definingClass("Landroidx/media3/exoplayer/audio/DefaultAudioSink\$DefaultAudioProcessorChain;")
    name("<init>")
    parameterTypes("[Landroidx/media3/common/audio/AudioProcessor;")
}

/** Equalizer: shapes the player's sound in its own audio chain. Part of the "Arsound" patch, not shown on its own. */
val equalizerPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        // The equalizer and the preamp run in the player's own audio chain, in software, so
        // they work where the phone has no audio effects (HyperOS). The processor goes first in the chain.
        audioProcessorChainMethod.addInstructions(
            0,
            """
                invoke-static { p1 }, $SOFT_EQUALIZER->withProcessor([Landroidx/media3/common/audio/AudioProcessor;)[Landroidx/media3/common/audio/AudioProcessor;
                move-result-object p1
            """,
        )
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
