package app.arsound.patches.soundcloud.library

import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

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

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        // The state is replaced at every exit of the method, so the banner is gone before the library
        // builds an item for it. The register that already holds the state is reused, so no free
        // register is needed. Later indexes are patched first, otherwise the earlier edits shift them.
        generateImportBannerStateMethod.apply {
            instructions
                .withIndex()
                .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN_OBJECT }
                .map { (index, _) -> index }
                .reversed()
                .forEach { returnIndex ->
                    val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA

                    addInstructions(
                        returnIndex,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->filterImportBannerState(Ljava/lang/Object;)Ljava/lang/Object;
                            move-result-object v$register
                            check-cast v$register, $BANNER_STATE_CLASS_DESCRIPTOR
                        """,
                    )
                }
        }
    }
}
