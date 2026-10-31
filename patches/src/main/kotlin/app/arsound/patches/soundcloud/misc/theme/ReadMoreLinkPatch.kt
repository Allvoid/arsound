package app.arsound.patches.soundcloud.misc.theme

import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val BUTTON_STYLE = "Lcom/soundcloud/android/ui/components/compose/buttons/ButtonStyle;"

/** The text of a description with its «Ещё» / «Свернуть» button: track pages, playlist pages, profile bios. */
private val BytecodePatchContext.descriptionWithLinkMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/components/compose/text/DescriptionWithLinkKt;")
    name("a")
    returnType("V")
}

/**
 * The static field of SoundCloud's ButtonStyle enum that holds the value of this name. The fields have short names
 * that change between versions; the names of the values are kept in the enum's static initializer.
 */
private fun BytecodePatchContext.buttonStyleField(valueName: String): String {
    val initializer = firstClassDef(BUTTON_STYLE).methods.first { it.name == "<clinit>" }
    val instructions = initializer.implementation?.instructions?.toList() ?: throw PatchException("ButtonStyle has no initializer")
    val named = instructions.indexOfFirst {
        it.opcode == Opcode.CONST_STRING && ((it as ReferenceInstruction).reference as StringReference).string == valueName
    }
    if (named < 0) throw PatchException("ButtonStyle.$valueName not found")
    for (index in named + 1 until instructions.size) {
        val instruction = instructions[index]
        if (instruction.opcode != Opcode.SPUT_OBJECT) continue
        val field = (instruction as ReferenceInstruction).reference as FieldReference
        if (field.definingClass == BUTTON_STYLE && field.type == BUTTON_STYLE) return field.name
    }
    throw PatchException("ButtonStyle.$valueName has no field")
}

/**
 * The «Ещё» button under a description: SoundCloud draws it as a pill of the screen's surface at 60 % with blue text,
 * a grey-blue patch over the cover or the wallpaper. It becomes a plain link: the Outlined style has a transparent
 * body and no border, and the link colour follows the theme's accent (ThemeTableBuilder.addColorTokens).
 * Part of the theme patch.
 */
val readMoreLinkPatch = bytecodePatch {
    compatibleWith("com.soundcloud.android")

    apply {
        val ghost = buttonStyleField("Ghost")
        val outlined = buttonStyleField("Outlined")
        descriptionWithLinkMethod.apply {
            val index = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET_OBJECT && fieldReference?.let {
                    it.definingClass == BUTTON_STYLE && it.name == ghost
                } == true
            }
            val register = getInstruction<OneRegisterInstruction>(index).registerA
            replaceInstruction(index, "sget-object v$register, $BUTTON_STYLE->$outlined:$BUTTON_STYLE")
        }
    }
}
