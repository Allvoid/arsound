package app.arsound.patches.soundcloud.power

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.returnType
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/power/PowerSavingPatch;"

/** Starts the 30 second unread messages polling of the title bar. */
private val BytecodePatchContext.inboxPollingMethod by gettingFirstMethodDeclaratively {
    name("onStateChanged")
    definingClass("Lcom/soundcloud/android/messages/inbox/titlebar/TitleBarInboxController\$attach\$1;")
}

/** Starts a playback item: {@code BaseExoPlayer.f(PlaybackItem)}. */
internal val BytecodePatchContext.playerPlayMethod by gettingFirstMethodDeclaratively {
    name("f")
    definingClass("Lcom/soundcloud/android/exoplayer/BaseExoPlayer;")
    returnType("V")
    parameterTypes("Lcom/soundcloud/android/playback/core/PlaybackItem;")
}

/** Updates the wake and Wi-Fi locks from the playback state: {@code ExoPlayerImpl.Q()}. */
internal val BytecodePatchContext.updateWakeAndWifiLockMethod by gettingFirstMethodDeclaratively {
    name("Q")
    definingClass("Landroidx/media3/exoplayer/ExoPlayerImpl;")
    returnType("V")
    parameterTypes()
}

/** Power saving: Adds an option to reduce background network polling. Part of the "Arsound" patch, not shown on its own. */
val powerSavingPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        playerPlayMethod.addInstruction(
            0,
            "invoke-static { p0, p1 }, Lapp/revanced/extension/soundcloud/power/PowerSavingPatch;->onPlaybackItem(Ljava/lang/Object;Ljava/lang/Object;)V",
        )
        updateWakeAndWifiLockMethod.apply {
            // The Wi-Fi lock call that takes the "playing" flag, not the constant "off".
            val wifiLockIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.definingClass == "Landroidx/media3/common/util/WifiLockManager;"
            }
            val register = getInstruction<FiveRegisterInstruction>(wifiLockIndex).registerD
            addInstructions(
                wifiLockIndex,
                """
                    invoke-static { v$register }, Lapp/revanced/extension/soundcloud/power/PowerSavingPatch;->keepWifiAwake(Z)Z
                    move-result v$register
                """,
            )
        }

        inboxPollingMethod.apply {
            // const-wide/16 v0, 0x1e; move-wide v2, v0 -- the same value is the initial delay and the period.
            val periodIndex = indexOfFirstInstructionOrThrow(Opcode.CONST_WIDE_16)
            val register = getInstruction<OneRegisterInstruction>(periodIndex).registerA
            addInstructions(
                periodIndex + 1,
                """
                    invoke-static { v$register, v${register + 1} }, $EXTENSION_CLASS_DESCRIPTOR->inboxPollSeconds(J)J
                    move-result-wide v$register
                """,
            )
        }
    }
}
