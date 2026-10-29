package app.arsound.patches.soundcloud.offline

import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * SoundCloud plays a track from its downloaded offline copy only when {@code OfflinePlaybackOperations.a(TrackUrn)}
 * returns true, and that method first requires {@code isOfflineContentEnabled()}. A free account has it off, so a
 * DRM track downloaded through the native offline path would never play back from its file. This method is the only
 * such gate: its own {@code DOWNLOADED} check stays, only the subscription flag is ignored.
 */
private val BytecodePatchContext.offlinePlaybackGateMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/offline/OfflinePlaybackOperations;")
    parameterTypes("Lcom/soundcloud/android/foundation/domain/TrackUrn;")
    returnType("Z")
}

/**
 * Lets a track downloaded through SoundCloud's native offline play from its offline copy without a subscription,
 * keeping the "is it actually downloaded" check. Part of "Download tracks", not shown on its own.
 */
internal val offlinePlaybackGatePatch = bytecodePatch {
    apply {
        offlinePlaybackGateMethod.apply {
            val flagIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE &&
                    methodReference?.name == "isOfflineContentEnabled"
            }
            // The result of isOfflineContentEnabled() lands in the move-result right after the call; force it to 1,
            // so the following "if not enabled, return false" is never taken, without touching the DOWNLOADED check.
            val register = getInstruction<OneRegisterInstruction>(flagIndex + 1).registerA
            addInstructions(flagIndex + 2, "const/4 v$register, 0x1")
        }
    }
}
