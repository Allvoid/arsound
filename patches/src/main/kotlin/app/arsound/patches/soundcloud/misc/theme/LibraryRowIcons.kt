package app.arsound.patches.soundcloud.misc.theme

import app.arsound.util.findFreeRegister
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.methodReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val THEME_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/theme/ArsoundTheme;"

/**
 * Where the theme's icon for a library row is asked for. The icon is read from the theme right before each
 * ActionListItem.ViewState(title, iconStart, iconEnd, ..., defaults) of a method, and the two sources differ only in what
 * identifies the row.
 */
internal sealed interface RowIconSource {
    /** Which of the [states] (indexes of the ViewState constructor calls of the method) get an icon. */
    fun statesToChange(states: List<Int>): List<Int>

    /**
     * Smali that leaves the row's icon resource in the result of the last call, before the state at [index] with
     * arguments [range] is built.
     */
    fun iconCall(method: MutableMethod, instructions: List<Instruction>, index: Int, range: RegisterRangeInstruction): String

    /**
     * A method that binds all the rows of the list: the row is the view the state is given to right after
     * (ActionListItem.n).
     */
    object ByView : RowIconSource {
        override fun statesToChange(states: List<Int>) = states

        override fun iconCall(
            method: MutableMethod,
            instructions: List<Instruction>,
            index: Int,
            range: RegisterRangeInstruction,
        ): String {
            val give = instructions.drop(index + 1).first { it.methodReference?.name == "n" } as FiveRegisterInstruction
            return "invoke-static/range { v${give.registerC} .. v${give.registerC} }, " +
                "$THEME_DESCRIPTOR->libraryRowIcon(Landroid/view/View;)I"
        }
    }

    /**
     * A method that only builds the state of one row and returns it, without the row view: the row is named instead,
     * as in its view id library_header_<row>. A method that builds none or several states is left alone.
     */
    class ByRowName(private val row: String) : RowIconSource {
        override fun statesToChange(states: List<Int>) = listOfNotNull(states.singleOrNull())

        override fun iconCall(
            method: MutableMethod,
            instructions: List<Instruction>,
            index: Int,
            range: RegisterRangeInstruction,
        ): String {
            val name = method.findFreeRegister(index, *IntArray(range.registerCount) { range.startRegister + it })
            return """
                const-string v$name, "$row"
                invoke-static { v$name }, $THEME_DESCRIPTOR->libraryRowIcon(Ljava/lang/String;)I
            """
        }
    }
}

/**
 * Before each ActionListItem.ViewState of the method, puts the theme's icon for the row (ArsoundTheme.libraryRowIcon,
 * asked as [source] says) into the iconStart argument and clears bit 2 of the last argument, a mask of the arguments
 * left at their defaults (in SoundCloud's code it marks the icon as default, so the icon would be dropped).
 * Returns the number of rows changed.
 */
internal fun MutableMethod.addLibraryRowIcons(source: RowIconSource): Int {
    val instructions = implementation!!.instructions.toList()
    val states = source.statesToChange(rowStateIndexes(instructions))
    // The last state first, so the indexes of the earlier ones stay valid.
    for (index in states.reversed()) {
        val range = instructions[index] as RegisterRangeInstruction
        addInstructions(index, source.iconCall(this, instructions, index, range) + iconArguments(instructions, index, range))
    }
    return states.size
}

private fun rowStateIndexes(instructions: List<Instruction>) =
    instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.INVOKE_DIRECT_RANGE && instruction.methodReference?.let {
            it.definingClass.endsWith("ActionListItem\$ViewState;") && it.name == "<init>"
        } == true
    }.map { it.index }

/** Takes the icon just returned into the iconStart argument and marks it as given in the mask of defaults. */
private fun iconArguments(
    instructions: List<Instruction>,
    index: Int,
    range: RegisterRangeInstruction,
): String {
    val icon = range.startRegister + 2
    val mask = range.startRegister + range.registerCount - 1
    val defaults = instructions.subList(0, index).last {
        it is NarrowLiteralInstruction && it is OneRegisterInstruction && it.registerA == mask
    } as NarrowLiteralInstruction
    return """
        move-result v$icon
        const/16 v$mask, ${defaults.narrowLiteral and 2.inv()}
    """
}
