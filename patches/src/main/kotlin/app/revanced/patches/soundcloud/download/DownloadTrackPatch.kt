package app.revanced.patches.soundcloud.download

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.util.getNode
import app.revanced.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/download/DownloadTrackPatch;"

private val downloadPermissionPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { document ->
            // Allows downloads without a system notification.
            document.getNode("manifest").appendChild(
                document.createElement("uses-permission").apply {
                    setAttribute("android:name", "android.permission.DOWNLOAD_WITHOUT_NOTIFICATION")
                },
            )
        }
    }
}

private const val PLAYLIST_EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/download/DownloadPlaylistPatch;"

@Suppress("unused")
val downloadTrackPatch = bytecodePatch(
    name = "Download tracks",
    description = "Adds a \"Download file\" button to the track menu and a download check to the playlist menu, for tracks whose artist enabled free downloads.",
) {
    dependsOn(settingsPatch, downloadPermissionPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        // Keep the OAuth helper to authorize the download request.
        oAuthConstructorMethod.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)
            addInstruction(returnIndex, "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->setOAuth(Ljava/lang/Object;)V")
        }

        trackMenuDataConsumerMethod.apply {
            val dialogIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.IGET_OBJECT && fieldReference?.type == "Landroid/app/Dialog;"
            }
            val dialogRegister = getInstruction<TwoRegisterInstruction>(dialogIndex).registerA

            val parseTrackIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.name == "parseTrack"
            }
            val trackUrnRegister = getInstruction<OneRegisterInstruction>(parseTrackIndex + 1).registerA

            addInstruction(
                parseTrackIndex + 2,
                "invoke-static { v$dialogRegister, v$trackUrnRegister }, " +
                    "$EXTENSION_CLASS_DESCRIPTOR->onTrackMenu(Landroid/app/Dialog;Ljava/lang/Object;)V",
            )
        }

        playlistMenuDataConsumerMethod.apply {
            val dialogIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.IGET_OBJECT && fieldReference?.type == "Landroid/app/Dialog;"
            }
            val dialogRegister = getInstruction<TwoRegisterInstruction>(dialogIndex).registerA

            addInstruction(
                dialogIndex + 1,
                "invoke-static { v$dialogRegister, p1 }, " +
                    "$PLAYLIST_EXTENSION_CLASS_DESCRIPTOR->onPlaylistMenu(Landroid/app/Dialog;Ljava/lang/Object;)V",
            )
        }

        // Show SoundCloud's "downloaded" icon in the track cells of tracks downloaded with this patch.
        trackMetaLabelMethod.apply {
            // The method has many registers, so the track item is passed with a range invoke at the start,
            // since "p0" cannot be used in a regular invoke next to the icon register.
            addInstruction(0, "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->setCurrentTrackItem(Ljava/lang/Object;)V")

            val offlineIconIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC && methodReference?.definingClass?.endsWith("/OfflineStatesKt;") == true
            }
            val iconRegister = getInstruction<OneRegisterInstruction>(offlineIconIndex + 1).registerA

            addInstructions(
                offlineIconIndex + 2,
                """
                    invoke-static { v$iconRegister }, $EXTENSION_CLASS_DESCRIPTOR->getDownloadIcon(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$iconRegister
                    check-cast v$iconRegister, Lcom/soundcloud/android/ui/components/labels/icons/DownloadIcon${'$'}ViewState;
                """,
            )
        }
    }
}
