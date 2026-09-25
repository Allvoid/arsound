package app.arsound.patches.soundcloud.download

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.definingClass
import app.revanced.patcher.patch.resourcePatch
import app.arsound.util.getNode
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.arsound.util.indexOfFirstInstructionReversedOrThrow
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
            // Reads tracks downloaded before a reinstall, which no longer belong to the app.
            document.getNode("manifest").appendChild(
                document.createElement("uses-permission").apply {
                    setAttribute("android:name", "android.permission.READ_MEDIA_AUDIO")
                },
            )
            document.getNode("manifest").appendChild(
                document.createElement("uses-permission").apply {
                    setAttribute("android:name", "android.permission.READ_EXTERNAL_STORAGE")
                    setAttribute("android:maxSdkVersion", "32")
                },
            )
        }
    }
}

private const val PLAYLIST_EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/download/DownloadPlaylistPatch;"

/** Download tracks: Adds a "Download file" button to the track menu and a download check to the playlist menu, for tracks whose artist enabled free downloads. Part of the "Arsound" patch, not shown on its own. */
/** The step of the Downloads screen that keeps only downloaded tracks and playlists. */
private val BytecodePatchContext.downloadsFilterMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass("Lcom/soundcloud/android/features/library/downloads/DownloadsDataSource${'$'}loadTracksAndPlaylists${'$'}2;")
}

/** Holds the offline states the Downloads screen listens to. */
private val BytecodePatchContext.offlinePropertiesProviderConstructorMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/offline/DefaultOfflinePropertiesProvider;")
}

val downloadTrackPatch = bytecodePatch {
    dependsOn(settingsPatch, downloadPermissionPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        // The Downloads screen lists the tracks downloaded by Arsound, as downloaded.
        downloadsFilterMethod.apply {
            val stateIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                        .reference.toString().endsWith("->toOfflineState(Lcom/soundcloud/android/foundation/domain/Urn;)Lcom/soundcloud/android/foundation/domain/offline/OfflineState;")
            }
            val urnRegister = getInstruction<com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction>(stateIndex).registerD
            val stateRegister = getInstruction<OneRegisterInstruction>(stateIndex + 1).registerA
            addInstructions(
                stateIndex + 2,
                """
                    invoke-static { v$stateRegister }, Lapp/revanced/extension/soundcloud/download/DownloadsScreen;->stateOnScreen(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$stateRegister
                    check-cast v$stateRegister, Lcom/soundcloud/android/foundation/domain/offline/OfflineState;
                """,
            )
            // The urn register receives the state, so the urn is kept before the call.
            addInstruction(stateIndex, "invoke-static { v$urnRegister }, Lapp/revanced/extension/soundcloud/download/DownloadsScreen;->beforeState(Ljava/lang/Object;)V")
            addInstruction(0, "invoke-static { p1 }, Lapp/revanced/extension/soundcloud/download/DownloadsScreen;->addArsoundTracks(Ljava/lang/Object;)V")
        }
        // Kept so the Downloads screen can be told to filter its list again after Arsound downloads change.
        offlinePropertiesProviderConstructorMethod.apply {
            addInstruction(
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                "invoke-static { p0 }, Lapp/revanced/extension/soundcloud/download/DownloadsScreen;->setOfflinePropertiesProvider(Ljava/lang/Object;)V",
            )
        }
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

        // Playlist cells: the spinning download icon while tracks started from the playlist are downloading.
        playlistMetaLabelMethod.apply {
            val offlineIconIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC && methodReference?.definingClass?.endsWith("/OfflineStatesKt;") == true
            }
            val iconRegister = getInstruction<OneRegisterInstruction>(offlineIconIndex + 1).registerA
            addInstructions(
                offlineIconIndex + 2,
                """
                    invoke-static { v$iconRegister, p0 }, $EXTENSION_CLASS_DESCRIPTOR->getPlaylistDownloadIcon(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$iconRegister
                    check-cast v$iconRegister, Lcom/soundcloud/android/ui/components/labels/icons/DownloadIcon${'$'}ViewState;
                """,
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
