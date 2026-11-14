package app.arsound.patches.soundcloud.misc.account

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.extension.sharedExtensionPatch
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val ACCOUNT_SWITCH = "Lapp/revanced/extension/soundcloud/login/AccountSwitch;"

/**
 * SoundCloud's authenticator, asked whether its account may be taken out: it says yes and starts wiping the user's data
 * (`UserDataPurger`), subscribing to that work with nothing to hear its end.
 */
private val BytecodePatchContext.accountRemovalAllowedMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/onboarding/auth/AuthenticatorService\$SoundCloudAuthenticator;")
    name("getAccountRemovalAllowed")
}

/**
 * Switching SoundCloud accounts (Arsound's list of accounts, `login.AccountSwitch`): the switch takes the account out of
 * Android's account manager and must wait until SoundCloud has wiped the old user's data before it puts the next
 * account in and starts the app again. The authenticator's plain `subscribe()` to the wipe is replaced by Arsound's,
 * which subscribes with callbacks. Part of the "Arsound" patch, not shown on its own.
 */
val accountSwitchPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        accountRemovalAllowedMethod.apply {
            val subscribe = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.let {
                    it.name == "subscribe" && it.parameterTypes.isEmpty() &&
                        it.definingClass == "Lio/reactivex/rxjava3/core/Completable;"
                } == true
            }
            // The Disposable it returns is not kept: no move-result follows, so a void call can stand in its place.
            check(getInstruction(subscribe + 1).opcode != Opcode.MOVE_RESULT_OBJECT) {
                "The authenticator keeps the subscription to the wipe; the switch of accounts cannot follow it"
            }
            val purge = getInstruction<FiveRegisterInstruction>(subscribe).registerC
            replaceInstruction(
                subscribe,
                "invoke-static { v$purge }, $ACCOUNT_SWITCH->subscribePurge(Ljava/lang/Object;)V",
            )
        }
    }
}
