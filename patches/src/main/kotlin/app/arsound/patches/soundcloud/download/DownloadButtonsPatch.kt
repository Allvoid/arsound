package app.arsound.patches.soundcloud.download

import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.indexOfFirstInstructionReversedOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/download/DownloadButtons;"

/** Every screen change of SoundCloud goes through this method, the subscription offer of a download button too. */
private val BytecodePatchContext.navigateMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/navigation/Navigator;")
    returnType("V")
    parameterTypes("Lcom/soundcloud/android/foundation/navigation/NavigationTarget;")
}

/** The "Download" item of the track menu; its last parameter shows the Go or Go+ badge. */
private val BytecodePatchContext.selectiveDownloadItemConstructor by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/features/bottomsheet/track/TrackMenuItem\$SelectiveDownload;")
    name("<init>")
    parameterTypes("Lcom/soundcloud/android/foundation/domain/TrackUrn;", "Z", "Z")
}

/** SoundCloud's plan features; the extension asks it whether the user's subscription includes downloads. */
private val BytecodePatchContext.featureOperationsConstructor by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/configuration/features/DefaultFeatureOperations;")
    name("<init>")
}

/**
 * SoundCloud download buttons: "Download" in the menus of tracks, playlists and albums, on the track page and in
 * "Your likes" download with Arsound instead of opening the subscription offer. Part of "Download tracks", not shown
 * on its own.
 */
internal val downloadButtonsPatch = bytecodePatch {
    dependsOn(settingsPatch)

    apply {
        navigateMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->interceptNavigation(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :navigate
                return-void
            """,
            ExternalLabel("navigate", navigateMethod.getInstruction(0)),
        )

        featureOperationsConstructor.apply {
            addInstructions(
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->setFeatureOperations(Ljava/lang/Object;)V",
            )
        }

        // Only parameters are changed before the super constructor call, which the verifier allows.
        selectiveDownloadItemConstructor.addInstructions(
            0,
            """
                invoke-static { p3 }, $EXTENSION_CLASS_DESCRIPTOR->showPlanBadge(Z)Z
                move-result p3
            """,
        )
    }
}
