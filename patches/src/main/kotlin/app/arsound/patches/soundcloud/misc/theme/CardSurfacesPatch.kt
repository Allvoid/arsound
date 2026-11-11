package app.arsound.patches.soundcloud.misc.theme

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val CARDS = "Lapp/revanced/extension/soundcloud/theme/CardSurfaces;"
private const val MODIFIER = "Landroidx/compose/ui/Modifier;"
private const val VIEW = "Landroid/view/View;"

/**
 * SoundCloud's swipeable list cell, which the track rows of Compose lists are made of (the likes, a playlist, the
 * search): `SwipeCellKt.a(height, modifier, enabled, onSwipe, content, composer, changed)`.
 */
private val BytecodePatchContext.swipeCellMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/components/compose/listviews/SwipeCellKt;")
    name("a")
    returnType("V")
    parameterTypes(
        "F",
        MODIFIER,
        "Z",
        "Lkotlin/jvm/functions/Function0;",
        "Landroidx/compose/runtime/internal/ComposableLambdaImpl;",
        "Landroidx/compose/runtime/Composer;",
        "I",
    )
}

/**
 * "Cards and outlines": the tracks of Compose lists lie on cards like those of Arsound's settings, when the user
 * switched them on (`CardSurfaces.cell`; off, the modifier goes back as it came). The rows of the Library get their
 * cards where they ask the theme for their icon (ThemePatch). Part of the theme patch.
 *
 * The card is added to the modifier of the cell's box where the cell makes it (the result of `fillMaxWidth`), not to
 * the modifier the cell is called with: the cell keeps that one for its next pass, and a card added to it was added
 * again at every pass. Right before that place the cell reads the view it is shown in (`LocalView`), which tells the
 * extension the colours of the screen.
 *
 * The idea is KayLight2's (github.com/KayLight2/arsound, branch feature/cards-and-borders).
 */
val cardSurfacesPatch = bytecodePatch {
    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        swipeCellMethod.apply {
            val fill = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC && methodReference?.let {
                    it.definingClass == "Landroidx/compose/foundation/layout/SizeKt;" && it.name == "fillMaxWidth\$default"
                } == true
            }
            val result = getInstruction(fill + 1)
            if (result.opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("The swipe cell does not keep its box modifier")
            val modifier = (result as OneRegisterInstruction).registerA
            // The view the cell read last before it made the modifier.
            val view = (fill - 1 downTo 0).firstNotNullOfOrNull { index ->
                val instruction = getInstruction(index)
                if (instruction.opcode == Opcode.CHECK_CAST &&
                    (instruction as ReferenceInstruction).reference.toString() == VIEW
                ) (instruction as OneRegisterInstruction).registerA else null
            } ?: throw PatchException("The swipe cell does not read its view before it makes its modifier")
            // The plain form of a call reaches the first sixteen registers only.
            if (modifier > 15 || view > 15) throw PatchException("The swipe cell keeps its modifier or its view out of reach")
            addInstructions(
                fill + 2,
                """
                    invoke-static { v$modifier, v$view }, $CARDS->cell($MODIFIER$VIEW)$MODIFIER
                    move-result-object v$modifier
                """,
            )
        }
    }
}
