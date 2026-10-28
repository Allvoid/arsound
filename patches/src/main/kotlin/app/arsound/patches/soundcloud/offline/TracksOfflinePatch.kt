package app.arsound.patches.soundcloud.offline

import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Track items of lists. Uses SYNC_MISSING, which waits for the server when any track is missing.
 */
private val BytecodePatchContext.hotTracksMethod by gettingFirstMethodDeclaratively {
    name("hotTracks")
    definingClass("Lcom/soundcloud/android/tracks/DefaultTrackItemRepository;")
}

/** Tracks stored on the device are listed without waiting for the server. */
internal val tracksOfflinePatch = bytecodePatch {
    apply {
        hotTracksMethod.apply {
            val tracksIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE &&
                    (this as ReferenceInstruction).reference.toString().contains("TrackRepository;->tracks(")
            }
            val call = getInstruction<FiveRegisterInstruction>(tracksIndex)
            val resultRegister = getInstruction<OneRegisterInstruction>(tracksIndex + 1).registerA
            addInstructions(
                tracksIndex + 2,
                """
                    invoke-static { v${call.registerC}, v${call.registerD}, v$resultRegister }, $OFFLINE_FIRST_DESCRIPTOR->localTracksFirst(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$resultRegister
                    check-cast v$resultRegister, Lio/reactivex/rxjava3/core/Observable;
                """,
            )
        }
    }
}
