package app.arsound.patches.soundcloud.misc.playservices

import app.revanced.patcher.extensions.removeInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/** Checks Google Play services when the main screen starts; logs "This device is not supported." without them. */
private val BytecodePatchContext.playServicesCheckMethod by gettingFirstMethodDeclaratively("This device is not supported.") {
    name("invoke")
}

/**
 * No Google Play services dialog: Without Google Play services (Huawei phones with HarmonyOS, other phones without
 * Google) SoundCloud showed "Device not compatible" on every start of the main screen, though everything works
 * without them. The check stays, the dialog is not shown. Part of the "Arsound" patch, not shown on its own.
 */
val playServicesDialogPatch = bytecodePatch {
    apply {
        playServicesCheckMethod.apply {
            val calls = implementation!!.instructions.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    (instruction as ReferenceInstruction).reference.toString()
                        .endsWith("GoogleApiAvailability;->showErrorDialogFragment(Landroid/app/Activity;II)Z")
            }.map { it.index }
            check(calls.isNotEmpty()) { "No Google Play services dialog found" }
            // The calls' results are not used, so they go without anything in their place. Last first: indices stay.
            calls.sortedDescending().forEach { removeInstruction(it) }
        }
    }
}
