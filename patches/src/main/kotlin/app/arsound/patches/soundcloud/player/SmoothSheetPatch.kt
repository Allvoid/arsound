package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
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
private const val TRACK_PAGE_PRESENTER = "Lcom/soundcloud/android/playback/ui/TrackPagePresenter;"
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

/** Moves one player page to the offset of the sheet's slide: 0 collapsed, 1 open. It creates the mini player's corner views, which is how it is told apart. */
private val BytecodePatchContext.slidePageMethod by gettingFirstMethodDeclaratively {
    definingClass(TRACK_PAGE_PRESENTER)
    name("l")
    returnType("V")
    parameterTypes("Landroid/view/View;", "F")
    instructions(
        method { definingClass == "Lcom/soundcloud/android/playback/ui/ArtworkView;" && name == "<init>" },
    )
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

    compatibleWith("com.soundcloud.android"("2026.10.14-release"))

    apply {
        // The range form everywhere: the methods have dozens of registers, and a plain invoke-static only reaches
        // the first sixteen of them.
        sheetStateMethod.addInstructions(
            0,
            "invoke-static/range { p1 .. p2 }, $SHEET_CLASS_DESCRIPTOR->onSheetState(ILjava/lang/Object;)V",
        )

        // A page beside the visible one leaves the slide at once; v0 is free before the first instruction.
        slidePageMethod.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p1 .. p2 }, $LAYERS_CLASS_DESCRIPTOR->skipSlide(Landroid/view/View;F)Z
                    move-result v0
                    if-eqz v0, :slide
                    return-void
                """,
                ExternalLabel("slide", getInstruction(0)),
            )
        }

        roundArtworkMethod.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p0 .. p1 }, $LAYERS_CLASS_DESCRIPTOR->round(Landroid/view/View;I)Z
                    move-result v0
                    if-nez v0, :round
                    return-void
                """,
                ExternalLabel("round", getInstruction(0)),
            )
        }

        squareArtworkMethod.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p0 .. p0 }, $LAYERS_CLASS_DESCRIPTOR->square(Landroid/view/View;)Z
                    move-result v0
                    if-nez v0, :square
                    return-void
                """,
                ExternalLabel("square", getInstruction(0)),
            )
        }

        setImageViewMethod.addInstructions(
            0,
            "invoke-static/range { p0 .. p0 }, $LAYERS_CLASS_DESCRIPTOR->created(Landroid/view/View;)V",
        )
    }
}
