package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.removeInstructions
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val INFINITE_TRANSITION_KT = "Landroidx/compose/animation/core/InfiniteTransitionKt;"
private const val TRANSITION_VALUE = "Landroidx/compose/animation/core/InfiniteTransition\$TransitionAnimationState;"

/** The middle of the 0.5 to 0.76 the fill moved between, as the bits of a float. */
private const val FROZEN_FILL = "0x3f2147ae"

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
 * Part of the "Arsound" patch, not shown on its own.
 */
val feedMuteIconPatch = bytecodePatch {
    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
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
