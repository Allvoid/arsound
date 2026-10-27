package app.arsound.patches.soundcloud.widget

import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.findInstructionIndicesReversed
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/widget/WidgetHeart;"
private const val WIDGET_BUILDER = "Lcom/soundcloud/android/playback/widget/PlayerWidgetRemoteViewsBuilder;"

/**
 * Builds the RemoteViews of the player widget: every update of the widget (a new track, a like, play and pause) goes
 * through it. It holds the track and the cover, and returns the finished views from two places.
 */
private val BytecodePatchContext.playerWidgetViewsMethod by gettingFirstMethodDeclaratively {
    definingClass(WIDGET_BUILDER)
    returnType("Lcom/soundcloud/android/playback/widget/PlayerWidgetRemoteViews;")
    parameterTypes(
        "Landroid/content/Context;",
        "Lcom/soundcloud/android/playback/widget/WidgetIntentFactory;",
        "Lcom/soundcloud/android/playback/widget/WidgetResourceProvider;",
    )
}

/**
 * Player widget heart: The heart of the player widget on the home screen takes the colour of the playing track's
 * cover, or the accent of the Arsound theme when the cover has none. The colour is chosen where the widget is built,
 * while the builder still holds the cover, and put on the finished views as a colour filter on the heart.
 * Part of the "Arsound" patch, not shown on its own.
 */
val playerWidgetHeartPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.10.14-release"))

    apply {
        // The extension finds the cover by its type: it has to be the only bitmap the builder holds.
        val bitmapFields = firstClassDef(WIDGET_BUILDER).fields.count { it.type == "Landroid/graphics/Bitmap;" }
        if (bitmapFields != 1) {
            throw PatchException("$WIDGET_BUILDER holds $bitmapFields bitmaps, not one: the widget heart would not find the cover")
        }

        playerWidgetViewsMethod.apply {
            // The cover is put on the views here, as the widget's picture.
            indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.let {
                    it.definingClass == "Landroid/widget/RemoteViews;" && it.name == "setImageViewBitmap"
                } == true
            }

            // The views are returned from two places (with the cover, and with SoundCloud's empty logo); both get
            // the heart. Back to front, so an inserted call does not move the next index.
            val returns = findInstructionIndicesReversed(Opcode.RETURN_OBJECT)
            if (returns.size != 2) {
                throw PatchException("The widget views are returned from ${returns.size} places, not two: the widget heart needs a new look at the method")
            }
            returns.forEach { index ->
                val views = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructions(
                    index,
                    "invoke-static { v$views }, $EXTENSION_CLASS_DESCRIPTOR->tint(Landroid/widget/RemoteViews;)V",
                )
            }

            // The builder and the app's context, before anything in the method overwrites them.
            addInstructions(
                0,
                "invoke-static { p0, p1 }, $EXTENSION_CLASS_DESCRIPTOR->begin(Ljava/lang/Object;Landroid/content/Context;)V",
            )
        }
    }
}
