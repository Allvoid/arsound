package app.arsound.patches.soundcloud.library

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.hooks.wrapEveryReturn
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/library/HideImportBannerPatch;"

private const val BANNER_STATE_CLASS_DESCRIPTOR =
    "Lcom/soundcloud/android/playlistimport/migrator/data/storage/PlaylistImportBannerHelper\$PlaylistImportBannerState;"

/**
 * Decides which playlist import banner the library shows, or that there is none.
 * Everything the banner does starts from the state this method returns.
 */
private val BytecodePatchContext.generateImportBannerStateMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playlistimport/migrator/data/storage/PlaylistImportBannerHelper;")
    name("generateImportBannerState")
    returnType(BANNER_STATE_CLASS_DESCRIPTOR)
}

/**
 * Hide the playlist import banner: Removes the "Transfer your gems" banner from the library.
 * Part of the "Arsound" patch, not shown on its own.
 */
val hideImportBannerPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        // The state is replaced at every exit of the method, so the banner is gone before the library
        // builds an item for it. The register that already holds the state is reused, so no free
        // register is needed. Later indexes are patched first, otherwise the earlier edits shift them.
        generateImportBannerStateMethod.wrapEveryReturn(
            Opcode.RETURN_OBJECT,
            "$EXTENSION_CLASS_DESCRIPTOR->filterImportBannerState(Ljava/lang/Object;)Ljava/lang/Object;",
            castTo = BANNER_STATE_CLASS_DESCRIPTOR,
        )
    }
}
