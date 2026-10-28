package app.arsound.patches.soundcloud.local

import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private val BytecodePatchContext.playlistImportSettingsScreenMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/settings/playlistimport/PlaylistImportSettingsScreenKt;")
}

/** The row "Import files from this phone" on SoundCloud's "Import my music" screen. */
internal val importEntryPatch = bytecodePatch {
    apply {
        // "Import files from this phone" below "Manage imported likes" on SoundCloud's "Import my music" screen.
        playlistImportSettingsScreenMethod.apply {
            val revertTitleIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET && fieldReference?.name == "playlist_import_settings_revert_transfer"
            }
            val rowIndex = indexOfFirstInstructionOrThrow(revertTitleIndex) {
                opcode == Opcode.INVOKE_STATIC_RANGE && methodReference?.definingClass?.endsWith("ActionListStandardKt;") == true
            }
            // ActionListStandard(changed, defaults, composer, modifier, title, subtitle, onClick).
            val composerRegister = getInstruction<RegisterRangeInstruction>(rowIndex).startRegister + 2
            addInstruction(
                rowIndex + 1,
                "invoke-static { v$composerRegister }, " +
                    "Lapp/revanced/extension/soundcloud/settings/SettingsEntry;->addImportEntry(Landroidx/compose/runtime/Composer;)V",
            )
        }
    }
}
