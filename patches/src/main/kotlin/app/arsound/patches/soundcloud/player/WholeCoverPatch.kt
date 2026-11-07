package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.hooks.GuardExit
import app.arsound.patches.soundcloud.misc.hooks.insertGuard
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
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
private const val WIDTH_LISTENER = "Lcom/soundcloud/android/artwork/PlayerTrackArtworkView\$OnWidthChangedListener;"

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

/** The artwork view took a new size: it tells the controller through a listener it forgets when it leaves the window. */
private val BytecodePatchContext.artworkSizeChangedMethod by gettingFirstMethodDeclaratively {
    definingClass(ARTWORK_VIEW)
    name("onSizeChanged")
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

        // After super.onSizeChanged; the size parameters are not read again, so p1 is free.
        artworkSizeChangedMethod.addInstructions(
            1,
            """
                iget-object p1, p0, $ARTWORK_VIEW->b:$WIDTH_LISTENER
                invoke-static { p0, p1 }, $EXTENSION_CLASS_DESCRIPTOR->onArtworkSized(Landroid/view/View;Ljava/lang/Object;)V
            """,
        )
    }
}
