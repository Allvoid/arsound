package app.arsound.patches.soundcloud.misc.theme

import app.arsound.util.findInstructionIndicesReversed
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val SURFACES = "Lapp/revanced/extension/soundcloud/wallpaper/WallpaperSurfaces;"
private const val COLORS = "Lcom/soundcloud/android/ui/components/compose/Colors;"

/** A small track cell of a Compose list (the likes, a playlist): {@code CellSmallTrackKt.b}. */
private val BytecodePatchContext.composeSmallTrackCellMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/components/compose/listviews/track/CellSmallTrackKt;")
    name("b")
    returnType("V")
}

/** The first parameters of the content lambda the Compose cell makes: clickable, click, playable, height, end content. */
private val CELL_CONTENT_PARAMETERS = listOf("Z", "Lkotlin/jvm/functions/Function0;", "Z", "F", "Lkotlin/jvm/functions/Function3;")

/** The view states of the track cells that may be highlighted: they pick the cell's background drawable. */
private val TRACK_CELL_STATES = listOf("CellSmallTrack", "CellMicroTrack", "CellSlideTrack")
    .map { "Lcom/soundcloud/android/ui/components/listviews/track/$it\$ViewState;" }

/** The end of a fragment's view being made: {@code Fragment.performViewCreated()}, which calls {@code onViewCreated}. */
private val BytecodePatchContext.fragmentViewCreatedMethod by gettingFirstMethodDeclaratively {
    name("performViewCreated")
    definingClass("Landroidx/fragment/app/Fragment;")
    returnType("V")
}

/** The Compose theme every SoundCloud screen is wrapped in: its default colour fills the screen. */
private val BytecodePatchContext.composeThemeMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/components/compose/ThemeKt;")
    returnType("V")
    parameterTypes("J", "Lkotlin/jvm/functions/Function2;", "Landroidx/compose/runtime/Composer;", "I", "I")
}

/** SoundCloud's own scaffold, which fills a screen with the same colour. */
private val BytecodePatchContext.composeScaffoldMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/components/compose/ScaffoldKt;")
    returnType("V")
    parameterTypes(
        "Landroidx/compose/ui/Modifier;",
        "Lkotlin/jvm/functions/Function2;",
        "Landroidx/compose/runtime/internal/ComposableLambdaImpl;",
        "Landroidx/compose/runtime/Composer;",
        "I",
        "I",
    )
}

/**
 * The title bar that folds away over a list, with a picture behind the title ("Your likes", the purchases page). Its
 * default colour, the screen's, is where the picture fades to at the bottom.
 */
private val BytecodePatchContext.collapsableAppBarMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/components/compose/toolbars/CollapsableAppBarKt;")
    returnType("V")
    parameterTypes(
        "Ljava/lang/String;",
        "Lcom/soundcloud/android/ui/components/compose/toolbars/CollapsableAppBarBackground;",
        "F",
        "Landroidx/compose/ui/Modifier;",
        "Lkotlin/jvm/functions/Function3;",
        "Lkotlin/jvm/functions/Function3;",
        "Lkotlin/jvm/functions/Function3;",
        "Landroidx/compose/ui/text/TextStyle;",
        "Landroidx/compose/ui/text/TextStyle;",
        "J",
        "J",
        "Landroidx/compose/runtime/Composer;",
        "I",
        "I",
    )
}

/** The cover blurred behind the title of a playlist or a profile: {@code a(changed, composer, modifier, url)}. */
private val BytecodePatchContext.blurredHeaderMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/components/compose/toolbars/BlurredHeaderBackgroundKt;")
    returnType("V")
    parameterTypes("I", "Landroidx/compose/runtime/Composer;", "Landroidx/compose/ui/Modifier;", "Ljava/lang/String;")
}

/** The header of a playlist: its cover, drawn blurred behind the title, and fades into the screen's colour. */
private val BytecodePatchContext.playlistHeaderMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/components/compose/headers/PlaylistDetailsSmallerArtworkHeaderKt;")
    returnType("V")
    parameterTypes(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Lcom/soundcloud/android/ui/components/compose/headers/ArtworkHeaderPlayingState;",
        "Z",
        "Lkotlin/jvm/functions/Function0;",
        "Lkotlin/jvm/functions/Function0;",
        "Landroidx/compose/ui/Modifier;",
        "Landroidx/compose/runtime/Composer;",
        "I",
        "I",
    )
}

/** A call of a colour of SoundCloud's palette: {@code Colors.<name>(Composer)}, a packed colour. */
private fun Instruction.isColourCall(name: String? = null) =
    opcode == Opcode.INVOKE_STATIC && methodReference?.let {
        it.definingClass == COLORS && it.returnType == "J" && (name == null || it.name == name)
    } == true

/**
 * Takes SoundCloud's surface colour of a Compose screen ({@code Colors.j(Composer)}, the colour resource
 * {@code dark_surface}) from the extension instead: same argument, same register for the result, and the extension
 * answers with the original or, on a main screen with a wallpaper, with transparent. Only the default colour of the
 * theme and the scaffold is touched; a caller that passes its own colour keeps it.
 */
private fun MutableMethod.replaceSurfaceColour() {
    replaceSurfaceColourAt(indexOfFirstInstructionOrThrow { isColourCall() })
}

private fun MutableMethod.replaceSurfaceColourAt(index: Int) {
    val composer = getInstruction<FiveRegisterInstruction>(index).registerC
    replaceInstruction(index, "invoke-static { v$composer }, $SURFACES->screenSurface(Landroidx/compose/runtime/Composer;)J")
}

/**
 * The wallpaper behind SoundCloud's main screens (wallpaper/WallpaperSurfaces): the screens of fragments get their
 * opaque fills taken off when their view is made, and the Compose screens get a transparent base colour. The window
 * itself carries the picture; it is set from the theme's activity callbacks. The title bars of Compose pages fade into
 * the same transparent colour, and the blurred cover behind a playlist's title is left out. Part of the theme patch.
 */
val wallpaperSurfacesPatch = bytecodePatch {
    compatibleWith("com.soundcloud.android")

    apply {
        fragmentViewCreatedMethod.apply {
            // Right after the fragment's own onViewCreated(view, state): the view is still in its register.
            val call = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.name == "onViewCreated"
            }
            val view = getInstruction<FiveRegisterInstruction>(call).registerD
            addInstruction(call + 1, "invoke-static { v$view }, $SURFACES->onFragmentViewCreated(Landroid/view/View;)V")
        }
        // The name of the surface colour: the first colour the theme asks for. The palette's other colours (texts,
        // accents) are its neighbours in the same class and must stay.
        val surfaceName = composeThemeMethod.let { method ->
            method.getInstruction<Instruction>(method.indexOfFirstInstructionOrThrow { isColourCall() }).methodReference!!.name
        }
        composeThemeMethod.replaceSurfaceColour()
        composeScaffoldMethod.replaceSurfaceColour()
        collapsableAppBarMethod.replaceSurfaceColour()
        playlistHeaderMethod.apply {
            // The screen's colour in the header: the fades at the top and the bottom and the tint of the blur. The texts
            // have colours of their own.
            val colours = implementation!!.instructions.withIndex().filter { it.value.isColourCall(surfaceName) }
                .map { it.index }
            colours.forEach { replaceSurfaceColourAt(it) }
            // The cover under the blur, made clear: the modifier that marks it as what the blur shows.
            val haze = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC && methodReference?.definingClass == "Ldev/chrisbanes/haze/HazeKt;"
            }
            val modifier = getInstruction<OneRegisterInstruction>(haze + 1).registerA
            val composer = getInstruction<FiveRegisterInstruction>(colours.first()).registerC
            addInstructions(
                haze + 2,
                """
                    invoke-static { v$modifier, v$composer }, $SURFACES->headerBackdrop(Landroidx/compose/ui/Modifier;Landroidx/compose/runtime/Composer;)Landroidx/compose/ui/Modifier;
                    move-result-object v$modifier
                """,
            )
            // The blur over the header with its dark tint: left out over the wallpaper.
            val blur = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC && methodReference?.definingClass == "Ldev/chrisbanes/haze/HazeChildKt;"
            }
            val plain = getInstruction<FiveRegisterInstruction>(blur).registerC
            val blurred = getInstruction<OneRegisterInstruction>(blur + 1).registerA
            addInstructions(
                blur + 2,
                """
                    invoke-static { v$blurred, v$plain, v$composer }, $SURFACES->headerHaze(Landroidx/compose/ui/Modifier;Landroidx/compose/ui/Modifier;Landroidx/compose/runtime/Composer;)Landroidx/compose/ui/Modifier;
                    move-result-object v$blurred
                """,
            )
            // The fade into the screen's colour: a soft shade over the wallpaper.
            val fade = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC_RANGE && methodReference?.let {
                    it.definingClass == "Landroidx/compose/ui/graphics/Brush\$Companion;" && it.name.startsWith("verticalGradient")
                } == true
            }
            val brush = getInstruction<OneRegisterInstruction>(fade + 1).registerA
            addInstructions(
                fade + 2,
                """
                    invoke-static { v$brush, v$composer }, $SURFACES->headerShade(Ljava/lang/Object;Landroidx/compose/runtime/Composer;)Ljava/lang/Object;
                    move-result-object v$brush
                    check-cast v$brush, Landroidx/compose/ui/graphics/Brush;
                """,
            )
        }
        blurredHeaderMethod.apply {
            // The first look at the address ("did it change?") comes right after the restart group starts, with the
            // group's composer: from there on the method works with the address the extension gives.
            val index = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE && methodReference?.let {
                    it.definingClass == "Landroidx/compose/runtime/Composer;" && it.returnType == "Z" &&
                        it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
                } == true
            }
            val call = getInstruction<FiveRegisterInstruction>(index)
            addInstructions(
                index,
                """
                    invoke-static { v${call.registerD}, v${call.registerC} }, $SURFACES->headerPicture(Ljava/lang/String;Landroidx/compose/runtime/Composer;)Ljava/lang/String;
                    move-result-object v${call.registerD}
                """,
            )
        }
        // A track that plays only as a preview (Go+) gets a highlight fill in the Compose cell's content: clear over a
        // wallpaper. The content is a lambda class made by the cell; its fill is the palette colour d.
        val cellContent = composeSmallTrackCellMethod.implementation!!.instructions.firstNotNullOf { instruction ->
            instruction.methodReference?.takeIf {
                it.name == "<init>" && it.parameterTypes.map(CharSequence::toString).take(5) == CELL_CONTENT_PARAMETERS
            }?.definingClass
        }
        var fills = 0
        firstClassDef(cellContent).methods.forEach { method ->
            method.findInstructionIndicesReversed { isColourCall("d") }.forEach { index ->
                val composer = method.getInstruction<FiveRegisterInstruction>(index).registerC
                method.replaceInstruction(
                    index,
                    "invoke-static { v$composer }, $SURFACES->previewCellFill(Landroidx/compose/runtime/Composer;)J",
                )
                fills++
            }
        }
        if (fills == 0) throw PatchException("The Compose track cell $cellContent has no highlight fill any more")

        // A highlighted track cell (a Go+ preview) reads its background from the extension: plain over a wallpaper.
        TRACK_CELL_STATES.forEach { type ->
            val constructors = firstClassDef(type).methods.filter { it.name == "<init>" }
            var found = false
            constructors.forEach { constructor ->
                constructor.findInstructionIndicesReversed {
                    opcode == Opcode.SGET && fieldReference?.name == "ripple_cell_highlight_drawable"
                }.forEach { index ->
                    val register = constructor.getInstruction<OneRegisterInstruction>(index).registerA
                    constructor.addInstructions(
                        index + 1,
                        """
                            invoke-static/range { v$register .. v$register }, $SURFACES->trackCellBackground(I)I
                            move-result v$register
                        """,
                    )
                    found = true
                }
            }
            if (!found) throw PatchException("$type does not pick ripple_cell_highlight_drawable any more")
        }
    }
}
