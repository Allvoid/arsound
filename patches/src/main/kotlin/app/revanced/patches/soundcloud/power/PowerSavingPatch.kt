package app.revanced.patches.soundcloud.power

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/power/PowerSavingPatch;"

/** Starts the 30 second unread messages polling of the title bar. */
private val BytecodePatchContext.inboxPollingMethod by gettingFirstMethodDeclaratively {
    name("onStateChanged")
    definingClass("Lcom/soundcloud/android/messages/inbox/titlebar/TitleBarInboxController\$attach\$1;")
}

@Suppress("unused")
val powerSavingPatch = bytecodePatch(
    name = "Power saving",
    description = "Adds an option to reduce background network polling.",
) {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
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
