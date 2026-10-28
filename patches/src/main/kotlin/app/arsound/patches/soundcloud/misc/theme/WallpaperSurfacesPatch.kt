package app.arsound.patches.soundcloud.misc.theme

import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val SURFACES = "Lapp/revanced/extension/soundcloud/wallpaper/WallpaperSurfaces;"
private const val COLORS = "Lcom/soundcloud/android/ui/components/compose/Colors;"

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
 * Takes SoundCloud's surface colour of a Compose screen ({@code Colors.j(Composer)}, the colour resource
 * {@code dark_surface}) from the extension instead: same argument, same register for the result, and the extension
 * answers with the original or, on a main screen with a wallpaper, with transparent. Only the default colour of the
 * theme and the scaffold is touched; a caller that passes its own colour keeps it.
 */
private fun MutableMethod.replaceSurfaceColour() {
    val index = indexOfFirstInstructionOrThrow {
        opcode == Opcode.INVOKE_STATIC && methodReference?.let { it.definingClass == COLORS && it.returnType == "J" } == true
    }
    val composer = getInstruction<FiveRegisterInstruction>(index).registerC
    replaceInstruction(index, "invoke-static { v$composer }, $SURFACES->screenSurface(Landroidx/compose/runtime/Composer;)J")
}

/**
 * The wallpaper behind SoundCloud's main screens (wallpaper/WallpaperSurfaces): the screens of fragments get their
 * opaque fills taken off when their view is made, and the Compose screens get a transparent base colour. The window
 * itself carries the picture; it is set from the theme's activity callbacks. Part of the theme patch.
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
        composeThemeMethod.replaceSurfaceColour()
        composeScaffoldMethod.replaceSurfaceColour()
    }
}
