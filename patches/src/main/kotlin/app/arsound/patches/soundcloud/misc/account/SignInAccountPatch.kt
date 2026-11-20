package app.arsound.patches.soundcloud.misc.account

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.extension.sharedExtensionPatch
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.method
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val SIGN_IN_ACCOUNT = "Lapp/revanced/extension/soundcloud/login/SignInAccount;"
private const val ACCOUNT_MANAGER = "Landroid/accounts/AccountManager;"

/** SoundCloud's sign-in: puts the account it got a token for into Android's account manager. */
private val BytecodePatchContext.addAccountMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/onboardingaccounts/AccountOperations;")
    returnType("Z")
    instructions(
        method { definingClass == ACCOUNT_MANAGER && name == "addAccountExplicitly" },
    )
}

/**
 * An account Android refuses at sign-in: SoundCloud's `addAccountExplicitly` goes through `login.SignInAccount`, which
 * tries other names when Android keeps a hidden account under the usual one, writes what it sees to the logs, and
 * explains the refusal instead of the bare "Could not sign in". Part of the "Arsound" patch, not shown on its own.
 */
val signInAccountPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        addAccountMethod.apply {
            val add = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.let {
                    it.definingClass == ACCOUNT_MANAGER && it.name == "addAccountExplicitly"
                } == true
            }
            val call = getInstruction<FiveRegisterInstruction>(add)
            val result = getInstruction(add + 1)
            check(result.opcode == Opcode.MOVE_RESULT) { "The sign-in does not read the answer of addAccountExplicitly" }
            val added = (result as OneRegisterInstruction).registerA
            val manager = call.registerC
            val account = call.registerD
            val password = call.registerE
            val extras = call.registerF

            // The account register takes the account that went in (another name, or null when refused): SoundCloud
            // goes on with it, sets its user data and token, and on null clears it itself.
            replaceInstruction(
                add,
                "invoke-static { v$manager, v$account, v$password, v$extras }, $SIGN_IN_ACCOUNT->add(" +
                    "${ACCOUNT_MANAGER}Landroid/accounts/Account;Ljava/lang/String;Landroid/os/Bundle;)" +
                    "Landroid/accounts/Account;",
            )
            replaceInstruction(add + 1, "move-result-object v$account")
            addInstructions(
                add + 2,
                """
                    invoke-static { v$account }, $SIGN_IN_ACCOUNT->added(Ljava/lang/Object;)Z
                    move-result v$added
                """,
            )
        }
    }
}
