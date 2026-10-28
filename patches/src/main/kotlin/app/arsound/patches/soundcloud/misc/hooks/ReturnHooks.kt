package app.arsound.patches.soundcloud.misc.hooks

import app.arsound.util.findInstructionIndicesReversed
import app.arsound.util.indexOfFirstInstructionReversedOrThrow
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/** The `move-result` that reads a static call's value, for the kind of value a return instruction returns. */
private fun moveResultFor(returnOpcode: Opcode) = when (returnOpcode) {
    Opcode.RETURN -> "move-result"
    Opcode.RETURN_OBJECT -> "move-result-object"
    Opcode.RETURN_WIDE -> "move-result-wide"
    else -> error("Not a value return: $returnOpcode")
}

/**
 * Passes the value of the return at [returnIndex] through an extension method: the value goes in, the answer comes out
 * in the same register, so no free register is needed.
 *
 * [hook] is the descriptor of the static extension method (`Lclass;->name(args)ret`). With [useRange] the call is
 * `invoke-static/range`, for a register above v15. [castTo] is the type of the method's return, when the hook can
 * only answer with `Object`.
 */
private fun MutableMethod.wrapReturnAt(returnIndex: Int, hook: String, castTo: String?, useRange: Boolean) {
    val returnInstruction = getInstruction<OneRegisterInstruction>(returnIndex)
    val register = "v${returnInstruction.registerA}"
    val invoke = if (useRange) "invoke-static/range { $register .. $register }" else "invoke-static { $register }"
    val cast = castTo?.let { "\ncheck-cast $register, $it" }.orEmpty()
    addInstructions(
        returnIndex,
        "$invoke, $hook\n${moveResultFor(returnInstruction.opcode)} $register$cast",
    )
}

/**
 * Passes the returned object through an extension method at the last `return-object` of the method. Earlier returns
 * are not covered.
 */
internal fun MutableMethod.wrapLastReturnedObject(hook: String, castTo: String? = null) =
    wrapReturnAt(indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT), hook, castTo, useRange = false)

/**
 * Passes the returned value through an extension method at every return of [returnOpcode]. The last return goes first,
 * so the indexes of the earlier ones stay valid.
 */
internal fun MutableMethod.wrapEveryReturn(
    returnOpcode: Opcode,
    hook: String,
    castTo: String? = null,
    useRange: Boolean = false,
) = findInstructionIndicesReversed(returnOpcode).forEach { wrapReturnAt(it, hook, castTo, useRange) }

/**
 * Adds [smali] just before the last `return-void` of the method, where the whole body has run.
 */
internal fun MutableMethod.insertBeforeLastReturnVoid(smali: String) =
    addInstruction(indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID), smali)

/**
 * Calls an extension method with `this` (p0) at the end of a constructor, after its fields are set. [hook] is the
 * descriptor of a static method taking the object.
 */
internal fun MutableMethod.hookConstructorEnd(hook: String) =
    insertBeforeLastReturnVoid("invoke-static { p0 }, $hook")
