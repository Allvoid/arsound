package app.arsound.patches.soundcloud.misc.theme

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType

private const val CARDS = "Lapp/revanced/extension/soundcloud/theme/CardSurfaces;"
private const val MODIFIER = "Landroidx/compose/ui/Modifier;"

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
 * switched them on (`CardSurfaces.cell`; off, the modifier goes back as it came). The cell takes its modifier from the
 * extension before anything looks at it, so Compose compares the same modifier at every pass. The rows of the Library
 * get their cards where they ask the theme for their icon (ThemePatch). Part of the theme patch.
 *
 * The idea is KayLight2's (github.com/KayLight2/arsound, branch feature/cards-and-borders).
 */
val cardSurfacesPatch = bytecodePatch {
    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        // A static method: p0 is the height, p1 the modifier. The range form of the call: the method has more than
        // sixteen registers, so its parameters are beyond the reach of the plain form, and the call was left out
        // without a word, which the verifier of Android then refused the whole class for.
        swipeCellMethod.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p1 }, $CARDS->cell($MODIFIER)$MODIFIER
                move-result-object p1
            """,
        )
    }
}
