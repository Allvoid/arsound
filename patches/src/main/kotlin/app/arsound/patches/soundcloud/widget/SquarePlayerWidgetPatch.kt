package app.arsound.patches.soundcloud.widget

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.manifest.addApplicationComponent
import app.arsound.util.ResourceGroup
import app.arsound.util.copyResources
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.returnType

private const val EXTENSION_CLASS = "app.revanced.extension.soundcloud.widget.SquarePlayerWidget"
private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/widget/SquarePlayerWidget;"

/** Hands SoundCloud's player widget to the home screen: every update of the widget goes through it. */
private val BytecodePatchContext.pushPlayerWidgetMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/widget/PlayerWidgetPresenter;")
    name("b")
    returnType("V")
    parameterTypes("Landroid/widget/RemoteViews;")
}

private val squarePlayerWidgetResourcePatch = resourcePatch {
    apply {
        copyResources(
            "soundcloud/widget",
            ResourceGroup("layout", "arsound_widget_square.xml", "arsound_widget_square_empty.xml"),
            ResourceGroup(
                "drawable",
                "arsound_widget_square_background.xml",
                "arsound_widget_square_scrim.xml",
                "arsound_widget_square_round.xml",
                "arsound_widget_play.xml",
                "arsound_widget_pause.xml",
                "arsound_widget_prev.xml",
                "arsound_widget_next.xml",
                "arsound_widget_heart.xml",
                "arsound_widget_heart_active.xml",
            ),
            ResourceGroup("xml", "arsound_widget_square_info.xml"),
        )
        document("AndroidManifest.xml").use { document ->
            val receiver = document.addApplicationComponent(
                "receiver",
                "android:name" to EXTENSION_CLASS,
                "android:exported" to "true",
                // The name in the launcher's widget list: the same in Russian and English.
                "android:label" to "Arsound 2×2",
            )
            val filter = document.createElement("intent-filter")
            filter.appendChild(document.createElement("action").apply {
                setAttribute("android:name", "android.appwidget.action.APPWIDGET_UPDATE")
            })
            receiver.appendChild(filter)
            receiver.appendChild(document.createElement("meta-data").apply {
                setAttribute("android:name", "android.appwidget.provider")
                setAttribute("android:resource", "@xml/arsound_widget_square_info")
            })
        }
    }
}

/**
 * Square player widget: A 2 x 2 player widget on the home screen, the cover with the track, the buttons and the heart
 * over it, filled by SoundCloud's own widget builder. Part of the "Arsound" patch, not shown on its own.
 */
val squarePlayerWidgetPatch = bytecodePatch {
    dependsOn(squarePlayerWidgetResourcePatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        pushPlayerWidgetMethod.addInstruction(
            0,
            "invoke-static { p0, p1 }, $EXTENSION_CLASS_DESCRIPTOR->onPlayerWidget(Ljava/lang/Object;Landroid/widget/RemoteViews;)V",
        )
    }
}
