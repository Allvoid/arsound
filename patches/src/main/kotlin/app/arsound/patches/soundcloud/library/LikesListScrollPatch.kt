package app.arsound.patches.soundcloud.library

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/library/LikesListScroll;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
private const val LAZY_LIST_STATE = "Landroidx/compose/foundation/lazy/LazyListState;"

/** "Your likes": the header, the list and its rows, composed from the state of the screen. */
private val BytecodePatchContext.trackLikesScreenMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/features/library/mytracks/compose/TrackLikesScreenKt;")
    name("a")
    returnType("V")
}

/**
 * Smooth scrolling of "Your likes": the screen remembers `derivedStateOf { listState.firstVisibleItemIndex }` and reads
 * it while it is composed, only to tell whether the list is at its top. A derived state wakes its readers on every
 * change of its value, so the whole screen was composed again each time a row left the top edge, every few frames of a
 * scroll. The screen also makes the content of its rows anew each time it is composed, so every visible row was composed
 * again with it: its view state rebuilt, with a call into the system for the network state and the line under the title,
 * about twenty rows a time. On a phone that took longer than a frame, several times a second.
 *
 * The calculation is wrapped so that it gives 0 at the top and 1 anywhere below it: the value the screen uses is the
 * same, but it changes only when the list leaves or reaches its top. Part of the "Arsound" patch, not shown on its own.
 */
val likesListScrollPatch = bytecodePatch {
    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        trackLikesScreenMethod.apply {
            val listState = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC && methodReference?.name == "rememberLazyListState"
            }
            val listStateRegister = getInstruction<OneRegisterInstruction>(listState + 1).registerA
            // derivedStateOf(calculation): the only call of its kind in the screen, right after the list's state.
            val derived = indexOfFirstInstructionOrThrow(listState) {
                (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) && methodReference?.let {
                    it.definingClass == "Landroidx/compose/runtime/SnapshotStateKt;" &&
                        it.returnType == "Landroidx/compose/runtime/State;" &&
                        it.parameterTypes.map(CharSequence::toString) == listOf(FUNCTION0)
                } == true
            }
            if (getInstruction(derived).opcode != Opcode.INVOKE_STATIC) {
                throw PatchException("The derived state of \"Your likes\" is made in an unexpected way")
            }
            val calculation = getInstruction<FiveRegisterInstruction>(derived).registerC

            // Only the calculation that reads the list's state, made just before (`new Calc(listState, kind)`).
            val made = (derived - 1 downTo listState).firstOrNull { index ->
                val instruction = getInstruction(index)
                instruction.opcode == Opcode.INVOKE_DIRECT && instruction.methodReference?.let {
                    it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() == LAZY_LIST_STATE
                } == true && (instruction as FiveRegisterInstruction).let {
                    it.registerC == calculation && it.registerD == listStateRegister
                }
            }
            // And whose value the screen reads as a number: the index of the first visible row.
            val read = indexOfFirstInstructionOrThrow(derived) {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.let {
                    it.definingClass == "Ljava/lang/Number;" && it.name == "intValue"
                } == true
            }
            if (made == null || read - derived > 16) {
                throw PatchException("\"Your likes\" no longer reads the first visible row as expected")
            }

            addInstructions(
                derived,
                """
                    invoke-static/range { v$calculation .. v$calculation }, $EXTENSION_CLASS_DESCRIPTOR->atTopOnly($FUNCTION0)$FUNCTION0
                    move-result-object v$calculation
                """,
            )
        }
    }
}
