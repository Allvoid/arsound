package app.arsound.patches.soundcloud.offline

import app.arsound.util.indexOfFirstInstructionReversedOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/offline/NativeOffline;"

/**
 * SoundCloud's own offline content operations. The extension keeps the instance to hand a DRM track to the native
 * offline download, which Arsound's own downloader cannot save.
 */
private val BytecodePatchContext.offlineContentOperationsConstructor by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/offline/DefaultOfflineContentOperations;")
    name("<init>")
}

/**
 * Captures SoundCloud's offline content operations so a DRM-protected track can be routed to its native offline
 * download. Part of "Download tracks", not shown on its own.
 */
internal val nativeOfflinePatch = bytecodePatch {
    apply {
        offlineContentOperationsConstructor.apply {
            addInstructions(
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->setOfflineOperations(Ljava/lang/Object;)V",
            )
        }
    }
}
