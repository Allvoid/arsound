package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.arsound.util.indexOfFirstInstructionReversedOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.removeInstructions
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val INFINITE_TRANSITION_KT = "Landroidx/compose/animation/core/InfiniteTransitionKt;"
private const val TRANSITION_VALUE = "Landroidx/compose/animation/core/InfiniteTransition\$TransitionAnimationState;"

/** The middle of the 0.5 to 0.76 the fill moved between, as the bits of a float. */
private const val FROZEN_FILL = "0x3f2147ae"

private const val RADIAL_SPINNER = "Lcom/soundcloud/android/ui/components/compose/progress/RadialSpinner;"
private const val SPACER = "Landroidx/compose/foundation/layout/SpacerKt;"
private const val FEED_CONTENT_STATE = "Lcom/soundcloud/android/features/feed/ui/models/FeedContentState;"

/**
 * The content of a Discover card (a ConstraintLayout lambda): among other things a loading spinner that stays
 * composed, only made "gone" by the layout while the card is not loading.
 */
private val BytecodePatchContext.discoverCardMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/features/feed/ui/components/FeedDiscoverKt${'$'}FeedDiscover${'$'}lambda${'$'}2${'$'}${'$'}inlined${'$'}ConstraintLayout${'$'}5;")
    name("invoke")
}

/** The "Tap to preview" hint over a Discover card, with a muted speaker in a circle that fills up and down. */
private val BytecodePatchContext.feedOverlayMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/features/feed/ui/components/FeedOverlayKt;")
    name("a")
    returnType("V")
}

/**
 * No endless animation in the "Tap to preview" hint: The fill of its circle moved without end and set a height, so the
 * Discover tab composed, measured and drew every frame until the card was tapped, with or without music. The animation
 * is gone and the fill stands still in the middle. Without it nothing asks for frames: a fill that only keeps the same
 * value would still wake the app on every frame.
 * Also the spinner of a Discover card, which turned without end while hidden, is made only while it shows.
 * Part of the "Arsound" patch, not shown on its own.
 */
val feedMuteIconPatch = bytecodePatch {
    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        // The spinner of a Discover card turned its circle without end, also while hidden (the layout only makes it
        // gone): the animation woke the app on every frame, in every card the pager keeps ready. It is drawn only
        // while the card is loading, which is when the layout shows it.
        discoverCardMethod.apply {
            val spinner = indexOfFirstInstructionOrThrow {
                methodReference?.definingClass == RADIAL_SPINNER && methodReference?.name == "b"
            }
            val stateInit = indexOfFirstInstructionReversedOrThrow(spinner) {
                methodReference?.name == "<init>" && methodReference?.parameterTypes?.toList() == listOf(FEED_CONTENT_STATE)
            }
            val state = getInstruction<FiveRegisterInstruction>(stateInit).registerD
            // Before the two constants that set the spinner's arguments: the register of the first is free there.
            // The check and the jump go in front of them, the Spacer for the other case after the call.
            val before = spinner - 2
            val free = getInstruction<OneRegisterInstruction>(before).registerA
            // The composer is the register of the call that the spinner is drawn with.
            val composer = getInstruction<FiveRegisterInstruction>(spinner).registerF
            // Other views of the card are tied to the spinner's place, so the place stays: a Spacer with the same
            // layout modifier stands where the hidden spinner was. (Without any view there, the card did not draw.)
            val modifier = getInstruction<FiveRegisterInstruction>(spinner).registerD
            val done = getInstruction(spinner + 1)
            addInstructionsWithLabels(
                spinner + 1,
                """
                    goto :done
                    :hidden
                    const/4 v$free, 0x0
                    invoke-static { v$modifier, v$composer, v$free }, $SPACER->Spacer(Landroidx/compose/ui/Modifier;Landroidx/compose/runtime/Composer;I)V
                """,
                ExternalLabel("done", done),
            )
            addInstructionsWithLabels(
                before,
                """
                    invoke-virtual { v$state }, $FEED_CONTENT_STATE->d()Z
                    move-result v$free
                    if-eqz v$free, :hidden
                """,
                ExternalLabel("hidden", getInstruction(spinner + 2)),
            )
        }
        feedOverlayMethod.apply {
            // The later place first: the indices of the earlier one stay valid.
            val valueRead = indexOfFirstInstructionOrThrow {
                methodReference?.definingClass == TRANSITION_VALUE && methodReference?.name == "getValue"
            }
            val fillRead = indexOfFirstInstructionOrThrow(valueRead) { methodReference?.name == "floatValue" } + 1
            val fill = getInstruction<OneRegisterInstruction>(fillRead).registerA
            removeInstructions(valueRead, fillRead - valueRead)
            replaceInstruction(valueRead, "const v$fill, $FROZEN_FILL")

            val transition = indexOfFirstInstructionOrThrow {
                methodReference?.definingClass == INFINITE_TRANSITION_KT && methodReference?.name == "c"
            }
            val animationSet = indexOfFirstInstructionOrThrow(transition) {
                methodReference?.definingClass == INFINITE_TRANSITION_KT && methodReference?.name == "a"
            } + 1
            val animation = getInstruction<OneRegisterInstruction>(animationSet).registerA
            val label = transition - 1 // The name of the transition is set right before it.
            removeInstructions(label, animationSet - label)
            replaceInstruction(label, "const/4 v$animation, 0x0") // Only moved on from here, no longer read.
        }
    }
}
