package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.hooks.GuardExit
import app.arsound.patches.soundcloud.misc.hooks.insertGuard
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.method
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/player/WholeCover;"

private const val ARTWORK_CONTROLLER = "Lcom/soundcloud/android/playback/ui/PlayerArtworkController;"
private const val ARTWORK_VIEW = "Lcom/soundcloud/android/artwork/PlayerTrackArtworkView;"

/**
 * The artwork controller's "width changed": it sets up the sideways slide of the stretched cover, from its left edge
 * to its right one over the length of the track.
 */
private val BytecodePatchContext.artworkWidthChangedMethod by gettingFirstMethodDeclaratively {
    definingClass(ARTWORK_CONTROLLER)
    name("a")
    returnType("V")
    parameterTypes()
    instructions(
        method { definingClass == "Lcom/soundcloud/android/player/progress/ProgressHelper;" && name == "<init>" },
    )
}

/**
 * Whole cover in the player: the open player shows the whole cover between the title and the waveform, on a blurred
 * copy of it, instead of a strip of the stretched cover that slides sideways. Part of the "Arsound" patch, not shown
 * on its own.
 */
val wholeCoverPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        // v0 is free before the first instruction; the controller keeps its artwork view in the field "a".
        artworkWidthChangedMethod.insertGuard(
            "invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->skipSlide(Landroid/view/View;)Z",
            GuardExit.ReturnVoid,
            prelude = "iget-object v0, p0, $ARTWORK_CONTROLLER->a:$ARTWORK_VIEW",
        )
    }
}
