package app.arsound.patches.soundcloud.misc.theme

import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val BUTTON_KT = "Lcom/soundcloud/android/ui/components/compose/buttons/ButtonKt;"

/** The parameter of ButtonKt.b that holds the button's body colour; its bit in the mask of defaulted parameters. */
private const val CONTAINER_COLOUR_PARAMETER = 8
/** Registers before the body colour in the call: onClick, style, size, modifier, padding, two flags, shape. */
private const val CONTAINER_COLOUR_REGISTER = 8

/** The text of a description with its «Ещё» / «Свернуть» button: track pages, playlist pages, profile bios. */
private val BytecodePatchContext.descriptionWithLinkMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/components/compose/text/DescriptionWithLinkKt;")
    name("a")
    returnType("V")
}

/**
 * The «Ещё» button under a description: SoundCloud draws it as a Ghost pill, the screen's surface at 60 % with blue
 * text, a grey-blue patch over the cover or the wallpaper. It becomes a plain link: the call passes a transparent body
 * colour instead of the style's own (the default bit of that parameter is cleared, its register already holds 0, which
 * is transparent), and the link colour follows the theme's accent (ThemeTableBuilder.addColorTokens).
 * Part of the theme patch.
 */
val readMoreLinkPatch = bytecodePatch {
    compatibleWith("com.soundcloud.android")

    apply {
        descriptionWithLinkMethod.apply {
            val call = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC_RANGE && methodReference?.let {
                    it.definingClass == BUTTON_KT && it.name == "b"
                } == true
            }
            val range = getInstruction<RegisterRangeInstruction>(call)
            val mask = range.startRegister + range.registerCount - 1
            val maskSet = (call - 1 downTo 0).firstOrNull { index ->
                val instruction = getInstruction(index)
                (instruction.opcode == Opcode.CONST_16 || instruction.opcode == Opcode.CONST) &&
                    (instruction as OneRegisterInstruction).registerA == mask
            } ?: throw PatchException("The defaults of the «Ещё» button are not set where expected")
            val defaults = getInstruction<NarrowLiteralInstruction>(maskSet).narrowLiteral
            val bit = 1 shl CONTAINER_COLOUR_PARAMETER
            if (defaults and bit == 0) throw PatchException("The «Ещё» button already passes its own body colour")
            replaceInstruction(maskSet, "const/16 v$mask, ${defaults and bit.inv()}")
            // Transparent, set right before the call so nothing in between can change it.
            addInstruction(call, "const-wide/16 v${range.startRegister + CONTAINER_COLOUR_REGISTER}, 0x0")
        }
    }
}
