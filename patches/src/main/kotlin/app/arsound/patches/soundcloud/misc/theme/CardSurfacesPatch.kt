package app.arsound.patches.soundcloud.misc.theme

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
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
 * The rows of SoundCloud's settings and menus (Compose, no view at hand): the class, and the call whose result is the
 * modifier of the row's box (the row's height). The card is drawn on that modifier, so the row's own click, padding and
 * content stay inside it.
 */
private val ROW_CALLS = listOf(
    Triple("Lcom/soundcloud/android/ui/components/compose/actionlists/ActionListItemKt;", "height-3ABfNKs", "SizeKt"),
    Triple("Lcom/soundcloud/android/ui/components/compose/actionlists/ActionListStandardKt;", "heightIn-VpY3zN4\$default", "SizeKt"),
    Triple("Lcom/soundcloud/android/ui/components/compose/actionlists/ActionListToggleKt;", "heightIn-VpY3zN4\$default", "SizeKt"),
    Triple("Lcom/soundcloud/android/ui/components/compose/actionlists/ActionListSelectableKt;", "heightIn-VpY3zN4\$default", "SizeKt"),
    Triple("Lcom/soundcloud/android/ui/components/compose/actionlists/ActionListItemWithHelperKt;", "heightIn-VpY3zN4\$default", "SizeKt"),
)

/** The tiles of carousels: the class and the call whose result is the modifier of the tile's fixed-width box. */
private val TILE_CALLS = listOf(
    Triple("Lcom/soundcloud/android/ui/components/compose/listviews/track/CellSlideTrackKt;", "width-3ABfNKs", "SizeKt"),
    Triple("Lcom/soundcloud/android/ui/components/compose/listviews/playlist/CellSlidePlaylistKt;", "width-3ABfNKs", "SizeKt"),
    Triple("Lcom/soundcloud/android/ui/components/compose/listviews/user/CellSlideUserKt;", "width-3ABfNKs", "SizeKt"),
)

/** The cells of lists that are views: playlists and tracks, bound by `n(ViewState)`. */
private val VIEW_CELLS = listOf(
    "Lcom/soundcloud/android/ui/components/listviews/playlist/CellSmallPlaylist;",
    "Lcom/soundcloud/android/ui/components/listviews/playlist/CellMicroPlaylist;",
    "Lcom/soundcloud/android/ui/components/listviews/track/CellSmallTrack;",
    "Lcom/soundcloud/android/ui/components/listviews/track/CellMicroTrack;",
    "Lcom/soundcloud/android/ui/components/listviews/user/CellMicroUser;",
)

/**
 * Puts `CardSurfaces.row` on the result of the first call of [callName] in this method: `modifier = call(...)` becomes
 * `modifier = row(call(...))`. False when the method has no such call. The plain form of an invoke reaches the first
 * sixteen registers only, so the range form is used.
 */
private fun MutableMethod.cardOnFirstCallResult(callClass: String, callName: String, hook: String): Boolean {
    val instructions = implementation?.instructions ?: return false
    val call = instructions.indexOfFirst {
        (it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            (it as ReferenceInstruction).methodReference?.let { ref ->
                ref.definingClass == callClass && ref.name == callName
            } == true
    }
    if (call < 0) return false
    val result = getInstruction(call + 1)
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("$definingClass->$name: the call to $callName keeps no modifier")
    val register = (result as OneRegisterInstruction).registerA
    addInstructions(
        call + 2,
        """
            invoke-static/range { v$register .. v$register }, $CARDS->$hook($MODIFIER)$MODIFIER
            move-result-object v$register
        """,
    )
    return true
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

        for ((type, callName, callOwner) in ROW_CALLS) {
            val hooked = firstClassDef(type).methods.count {
                it.cardOnFirstCallResult("Landroidx/compose/foundation/layout/$callOwner;", callName, "row")
            }
            if (hooked == 0) throw PatchException("$type has no $callName any more")
        }

        for ((type, callName, callOwner) in TILE_CALLS) {
            val hooked = firstClassDef(type).methods.count {
                it.cardOnFirstCallResult("Landroidx/compose/foundation/layout/$callOwner;", callName, "tile")
            }
            if (hooked == 0) throw PatchException("$type has no $callName any more")
        }

        for (type in VIEW_CELLS) {
            val bind = firstClassDef(type).methods.singleOrNull { it.name == "n" && it.parameterTypes.size == 1 }
                ?: throw PatchException("$type has no cell binding n(ViewState) any more")
            // After the cell has set its own background, if it does; else first thing. Not at the return: some
            // binders reuse the register of the view for something else, and the verifier then refuses the class.
            val background = bind.implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL && (it as ReferenceInstruction).methodReference?.name == "setBackgroundResource"
            }
            bind.addInstruction(
                if (background >= 0) background + 1 else 0,
                "invoke-static/range { p0 .. p0 }, $CARDS->styleCell($VIEW)V",
            )
        }
    }
}
