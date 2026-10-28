package app.arsound.patches.soundcloud.offline

import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/** Profiles with their follow and block state. Uses SYNC_MISSING, which waits for the server. */
private val BytecodePatchContext.hotUsersMethod by gettingFirstMethodDeclaratively {
    name("hotUsers")
    definingClass("Lcom/soundcloud/android/user/data/DefaultUserItemRepository;")
}

/** Profiles stored on the device are shown without waiting for the server. */
internal val usersOfflinePatch = bytecodePatch {
    apply {
        hotUsersMethod.apply {
            val usersIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE &&
                    (this as ReferenceInstruction).reference.toString().contains("UserRepository;->users(")
            }
            val call = getInstruction<FiveRegisterInstruction>(usersIndex)
            replaceInstruction(
                usersIndex,
                "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE} }, $OFFLINE_FIRST_DESCRIPTOR->localUsersFirst(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            )
            val resultRegister = getInstruction<OneRegisterInstruction>(usersIndex + 1).registerA
            addInstruction(usersIndex + 2, "check-cast v$resultRegister, Lio/reactivex/rxjava3/core/Observable;")
        }
    }
}
