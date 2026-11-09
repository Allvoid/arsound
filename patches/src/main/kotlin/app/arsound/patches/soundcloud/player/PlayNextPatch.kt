package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/player/PlayNext;"
private const val QUEUE_MANAGER = "Lcom/soundcloud/android/features/playqueue/PlayQueueManager;"
private const val QUEUE_CONTEXT = "Lcom/soundcloud/android/foundation/playqueue/PlayQueueContext;"
private const val INSERT_AT = "$QUEUE_MANAGER->n(I[Lcom/soundcloud/android/foundation/domain/TrackUrn;Ljava/lang/String;)$QUEUE_CONTEXT"

/** "Play next" of every menu: works out the place after the tracks put next before, then inserts there with n(). */
private val BytecodePatchContext.playNextMethod by gettingFirstMethodDeclaratively {
    definingClass(QUEUE_MANAGER)
    name("p")
    returnType(QUEUE_CONTEXT)
    parameterTypes("Ljava/lang/String;", "[Lcom/soundcloud/android/foundation/domain/TrackUrn;")
}

/**
 * "Play next" puts the track right after the playing one, after the tracks put next before it (they play in the order they were
 * tapped), and then the queue goes on as it was; in "My Wave" too, whose batches SoundCloud counted as put next, and without the
 * place one too early that made the playing track's number point at the new one. Part of the "Arsound" patch, not shown on its own.
 */
val playNextPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        playNextMethod.apply {
            val index = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.toString() == INSERT_AT
            }
            val call = getInstruction<FiveRegisterInstruction>(index)
            if (call.registerCount != 4) throw PatchException("The insert of \"Play next\" has another shape")
            if (getInstruction(index + 1).opcode != Opcode.MOVE_RESULT_OBJECT ||
                getInstruction(index + 2).opcode != Opcode.RETURN_OBJECT
            ) {
                throw PatchException("\"Play next\" does more after its insert than expected")
            }
            val result = getInstruction<OneRegisterInstruction>(index + 1).registerA
            // Same registers: the manager, SoundCloud's place, the urns, the start page; the instruction keeps its labels.
            replaceInstruction(
                index,
                "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE}, v${call.registerF} }, " +
                    "$EXTENSION_CLASS_DESCRIPTOR->insert(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
            )
            // The extension answers with an Object: cast back before it is returned.
            addInstructions(index + 2, "check-cast v$result, $QUEUE_CONTEXT")
        }
    }
}
