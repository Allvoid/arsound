package app.arsound.patches.soundcloud.misc.hooks

import app.arsound.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction

/**
 * Makes sure each fingerprint found its method, though the patch changes nothing in it. The extension calls these
 * methods by name, so a SoundCloud update that renames one must fail the patch here and not the app later.
 */
internal fun requireResolved(vararg methods: Method) = methods.forEach { it.name }

/** Fails the patch when the method has no instruction that matches [filter]; the method is not changed. */
internal fun Method.requireInstruction(filter: Instruction.() -> Boolean) {
    indexOfFirstInstructionOrThrow(0, filter)
}
