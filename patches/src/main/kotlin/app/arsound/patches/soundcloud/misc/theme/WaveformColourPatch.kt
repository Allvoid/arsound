package app.arsound.patches.soundcloud.misc.theme

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch

/** The waveform's size changes when it is laid out first, long after its size change: p0 is still the view there. */
private val BytecodePatchContext.waveformViewSizeMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/player/progress/waveform/WaveformView;")
    name("onSizeChanged")
    parameterTypes("I", "I", "I", "I")
}

/**
 * The waveform of the player in the accent of the theme: its colours are plain numbers in SoundCloud's style, which the
 * theme's colour table does not reach. Part of the "Arsound" patch, not shown on its own.
 */
val waveformColourPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        waveformViewSizeMethod.addInstruction(
            0,
            "invoke-static { p0 }, Lapp/revanced/extension/soundcloud/theme/WaveformColours;->recolour(Landroid/view/View;)V",
        )
    }
}
