package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.hooks.FiresOn
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

private const val SHEET_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/player/PlayerSheet;"
private const val LAYERS_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/player/PlayerArtworkLayers;"

private const val ARTWORK_VIEW = "Lcom/soundcloud/android/artwork/PlayerTrackArtworkView;"
private const val SHAPEABLE_IMAGE_VIEW = "Lcom/google/android/material/imageview/ShapeableImageView;"

/**
 * Tells what state the player sheet is in (dragged, settling, open, collapsed). Found by the name of its class and
 * by the lines its states log, which a renamed or rewritten method would lose.
 */
private val BytecodePatchContext.sheetStateMethod by gettingFirstMethodDeclaratively(
    "onStateChanged: STATE_COLLAPSED",
    "onStateChanged: STATE_EXPANDED",
    "onStateChanged: STATE_DRAGGING",
) {
    definingClass("Lcom/soundcloud/android/playback/ui/SlidingPlayerController\$1;")
    name("c")
    returnType("V")
    parameterTypes("I", "Landroid/view/View;")
}

/** Gives the page's artwork its rounded corners, with the mini player's radius. */
private val BytecodePatchContext.roundArtworkMethod by gettingFirstMethodDeclaratively {
    definingClass(ARTWORK_VIEW)
    name("a")
    returnType("V")
    parameterTypes("I")
    instructions(
        method { definingClass == SHAPEABLE_IMAGE_VIEW && name == "setShapeAppearanceModel" },
    )
}

/** Gives the page's artwork square corners, as in the open player. */
private val BytecodePatchContext.squareArtworkMethod by gettingFirstMethodDeclaratively {
    definingClass(ARTWORK_VIEW)
    name("b")
    returnType("V")
    parameterTypes()
    instructions(
        method { definingClass == SHAPEABLE_IMAGE_VIEW && name == "setShapeAppearanceModel" },
    )
}

/** Remembers the artwork's main image view; called while the artwork is made. */
private val BytecodePatchContext.setImageViewMethod by gettingFirstMethodDeclaratively {
    definingClass(ARTWORK_VIEW)
    name("setWrappedImageView")
    returnType("V")
    parameterTypes(SHAPEABLE_IMAGE_VIEW)
}

/**
 * Smooth player collapse: The player used to drop to about ten frames a second on its way down, because the
 * artwork of every page was redrawn in a picture as big as the screen on each frame of the slide, more than the
 * graphics cache holds. The artwork keeps those pictures only while its corners are rounded, does not set corners
 * it already has, and the pages beside the one in view sit the slide out. Also tells the extension what the sheet
 * does, which it times for the playback log and uses to let the theme animation and other per-frame work wait.
 * Part of the "Arsound" patch, not shown on its own.
 */
val smoothSheetPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        // The range form everywhere: the methods have dozens of registers, and a plain invoke-static only reaches
        // the first sixteen of them.
        sheetStateMethod.addInstructions(
            0,
            "invoke-static/range { p1 .. p2 }, $SHEET_CLASS_DESCRIPTOR->onSheetState(ILjava/lang/Object;)V",
        )

        // A page beside the visible one leaves the slide at once; v0 is free before the first instruction.
        slidePlayerPageMethod.insertGuard(
            "invoke-static/range { p1 .. p2 }, $LAYERS_CLASS_DESCRIPTOR->skipSlide(Landroid/view/View;F)Z",
            GuardExit.ReturnVoid,
        )

        roundArtworkMethod.insertGuard(
            "invoke-static/range { p0 .. p1 }, $LAYERS_CLASS_DESCRIPTOR->round(Landroid/view/View;I)Z",
            GuardExit.ReturnVoid,
            FiresOn.FALSE,
        )

        squareArtworkMethod.insertGuard(
            "invoke-static/range { p0 .. p0 }, $LAYERS_CLASS_DESCRIPTOR->square(Landroid/view/View;)Z",
            GuardExit.ReturnVoid,
            FiresOn.FALSE,
        )

        setImageViewMethod.addInstructions(
            0,
            "invoke-static/range { p0 .. p0 }, $LAYERS_CLASS_DESCRIPTOR->created(Landroid/view/View;)V",
        )
    }
}
