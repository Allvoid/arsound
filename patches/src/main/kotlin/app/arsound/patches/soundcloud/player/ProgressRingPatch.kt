package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.definingClass
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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

private const val ANIMATION_SPEC_KT = "Landroidx/compose/animation/core/AnimationSpecKt;"

/** The play button of the mini player, with a ring of the track's progress around it. */
private val BytecodePatchContext.miniPlayerRingMethod by gettingFirstMethodDeclaratively(
    "PlayPauseProgressButtonAnimation",
) {
    definingClass("Lcom/soundcloud/android/ui/components/compose/buttons/PlayPauseProgressButtonKt;")
    name("a")
    returnType("V")
}

/** The same ring around the play button of the Feed tab. */
private val BytecodePatchContext.feedRingMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/features/feed/ui/components/FeedPlayProgressButtonKt;")
    name("a")
    returnType("V")
}

/** Makes the first tween of the method end at once: its duration, set shortly before the call, becomes zero. */
private fun MutableMethod.endTweenAtOnce() {
    val tween = indexOfFirstInstructionOrThrow {
        opcode == Opcode.INVOKE_STATIC && methodReference?.definingClass == ANIMATION_SPEC_KT &&
            methodReference?.name == "d"
    }
    val duration = getInstruction<FiveRegisterInstruction>(tween).registerC
    val durationSet = (tween - 1 downTo 0).firstOrNull { index ->
        val instruction = getInstruction(index)
        instruction is OneRegisterInstruction && instruction.registerA == duration &&
            instruction is WideLiteralInstruction
    } ?: throw PatchException("The duration of the progress ring is not set where expected")
    replaceInstruction(durationSet, "const/4 v$duration, 0x0")
}

/**
 * No endless animation of the progress ring: The ring around the play button took about 3/4 of a second to reach each
 * new position, and the next position came before it got there. So while music played the ring never stopped
 * animating: the app composed and drew every frame, 120 a second, on any screen, for a change of less than a pixel.
 * The ring now takes each new position at once, so a frame is drawn only when the player reports its position.
 * Part of the "Arsound" patch, not shown on its own.
 */
val progressRingPatch = bytecodePatch {
    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        miniPlayerRingMethod.endTweenAtOnce()
        feedRingMethod.endTweenAtOnce()
    }
}
