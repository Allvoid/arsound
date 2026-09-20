package app.arsound.patches.soundcloud.debug

import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/debug/PlayerBarLog;"

private const val TRACK_PAGE_PRESENTER = "Lcom/soundcloud/android/playback/ui/TrackPagePresenter;"

/** Fills the player page with a track. */
private val BytecodePatchContext.bindPlayerPageMethod by gettingFirstMethodDeclaratively {
    definingClass(TRACK_PAGE_PRESENTER)
    name("i")
    returnType("V")
    parameterTypes("Landroid/view/View;", "Lcom/soundcloud/android/playback/ui/PlayerItem;")
}

/** Moves the player page to another queue item, emptying what was shown. */
private val BytecodePatchContext.resetPlayerPageMethod by gettingFirstMethodDeclaratively {
    definingClass(TRACK_PAGE_PRESENTER)
    name("a")
    returnType("V")
    parameterTypes("Landroid/view/View;", "Lcom/soundcloud/android/foundation/playqueue/PlayQueueItem;")
}

/** Tells the player page which playback state to show. */
private val BytecodePatchContext.playStatePlayerPageMethod by gettingFirstMethodDeclaratively {
    definingClass(TRACK_PAGE_PRESENTER)
    name("c")
    returnType("V")
    parameterTypes("Landroid/view/View;", "Lcom/soundcloud/android/playback/session/PlayState;", "Z")
}

/**
 * Player log: Records what the player page is asked to show, to catch the collapsed player bar
 * staying empty. Writes to the log only, and only while the log setting is on.
 * Part of the "Arsound" patch, not shown on its own.
 */
val playerBarLogPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        // The range form is required: these methods use dozens of registers, and a plain
        // invoke-static only reaches the first sixteen of them.
        bindPlayerPageMethod.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS_DESCRIPTOR->logBind(Ljava/lang/Object;Ljava/lang/Object;)V
            """,
        )
        resetPlayerPageMethod.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS_DESCRIPTOR->logReset(Ljava/lang/Object;Ljava/lang/Object;)V
            """,
        )
        playStatePlayerPageMethod.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS_DESCRIPTOR->logPlayState(Ljava/lang/Object;Ljava/lang/Object;)V
            """,
        )
    }
}
