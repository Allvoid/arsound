package app.revanced.patches.soundcloud.offline

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/offline/OfflineFirstPatch;"

private const val FUNCTION_CLASS =
    "Lcom/soundcloud/android/playlists/DataSourceProvider\$fetchAndSyncPlaylistOrFallbackToLocal\$1;"

/**
 * Runs once the stored playlist with its tracks is found. For playlists of other users it
 * requests the server copy and waits for it before the screen gets any data.
 */
private val BytecodePatchContext.fetchAndSyncPlaylistMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass(FUNCTION_CLASS)
}

/**
 * Builds the playlist screen streams. Its track list waits for a full playlist sync when the
 * last sync is older than 24 hours.
 */
private val BytecodePatchContext.playlistScreenStreamsMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass("Lcom/soundcloud/android/playlists/DataSourceProvider\$playlistWithExtras\$1\$2;")
}

/**
 * Track items of lists. Uses SYNC_MISSING, which waits for the server when any track is missing.
 */
private val BytecodePatchContext.hotTracksMethod by gettingFirstMethodDeclaratively {
    name("hotTracks")
    definingClass("Lcom/soundcloud/android/tracks/DefaultTrackItemRepository;")
}

@Suppress("unused")
val offlineFirstPatch = bytecodePatch(
    name = "Offline first playlists",
    description = "Shows stored playlists and albums immediately and refreshes them in the background.",
) {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        fetchAndSyncPlaylistMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->isEnabled()Z
                move-result v0
                if-eqz v0, :original
                iget-object v0, p0, $FUNCTION_CLASS->a:Lcom/soundcloud/android/playlists/DataSourceProvider;
                iget-object v0, v0, Lcom/soundcloud/android/playlists/DataSourceProvider;->c:Lcom/soundcloud/android/foundation/domain/playlists/PlaylistWithTracksRepository;
                iget-object v1, p0, $FUNCTION_CLASS->c:Lcom/soundcloud/android/foundation/domain/Urn;
                invoke-static { v0, v1 }, $EXTENSION_CLASS_DESCRIPTOR->syncInBackground(Ljava/lang/Object;Ljava/lang/Object;)V
                iget-object v0, p0, $FUNCTION_CLASS->d:Lcom/soundcloud/android/foundation/domain/repository/SingleItemResponse;
                invoke-static { v0 }, Lio/reactivex/rxjava3/core/Single;->n(Ljava/lang/Object;)Lio/reactivex/rxjava3/internal/operators/single/SingleJust;
                move-result-object v0
                return-object v0
            """,
            ExternalLabel("original", fetchAndSyncPlaylistMethod.getInstruction(0)),
        )

        playlistScreenStreamsMethod.apply {
            val syncIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.NEW_INSTANCE &&
                    (this as ReferenceInstruction).reference.toString() ==
                    "Lio/reactivex/rxjava3/internal/operators/completable/CompletableFromSingle;"
            }
            // new-instance v6, CompletableFromSingle; invoke-direct {v6, v5}; goto :goto_0
            val waitRegister = getInstruction<OneRegisterInstruction>(syncIndex).registerA
            val gotoIndex = syncIndex + 2
            addInstructionsWithLabels(
                gotoIndex,
                """
                    invoke-static { v$waitRegister }, $EXTENSION_CLASS_DESCRIPTOR->detachPlaylistSync(Ljava/lang/Object;)Z
                    move-result v9
                    if-eqz v9, :wait
                    sget-object v$waitRegister, Lio/reactivex/rxjava3/internal/operators/completable/CompletableEmpty;->a:Lio/reactivex/rxjava3/internal/operators/completable/CompletableEmpty;
                """,
                ExternalLabel("wait", getInstruction(gotoIndex)),
            )
        }

        playlistScreenStreamsMethod.apply {
            // The creator follow status waits for the followings sync. Start it with "unknown".
            val statusMapperIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET_OBJECT &&
                    (this as ReferenceInstruction).reference.toString().contains("otherUserStatusForMe")
            }
            val resultIndex = statusMapperIndex + 2
            val statusRegister = getInstruction<OneRegisterInstruction>(resultIndex).registerA
            val freeRegister = getInstruction<OneRegisterInstruction>(statusMapperIndex).registerA
            addInstructionsWithLabels(
                resultIndex + 1,
                """
                    invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->isEnabled()Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :keep
                    sget-object v$freeRegister, Lcom/soundcloud/android/playlists/PlaylistDetailsMetadata${'$'}FollowStatus;->a:Lcom/soundcloud/android/playlists/PlaylistDetailsMetadata${'$'}FollowStatus;
                    invoke-virtual { v$statusRegister, v$freeRegister }, Lio/reactivex/rxjava3/core/Observable;->P(Ljava/lang/Object;)Lio/reactivex/rxjava3/core/Observable;
                    move-result-object v$statusRegister
                """,
                ExternalLabel("keep", getInstruction(resultIndex + 1)),
            )
        }

        hotTracksMethod.apply {
            val tracksIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE &&
                    (this as ReferenceInstruction).reference.toString().contains("TrackRepository;->tracks(")
            }
            val call = getInstruction<FiveRegisterInstruction>(tracksIndex)
            val resultRegister = getInstruction<OneRegisterInstruction>(tracksIndex + 1).registerA
            addInstructions(
                tracksIndex + 2,
                """
                    invoke-static { v${call.registerC}, v${call.registerD}, v$resultRegister }, $EXTENSION_CLASS_DESCRIPTOR->localTracksFirst(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$resultRegister
                    check-cast v$resultRegister, Lio/reactivex/rxjava3/core/Observable;
                """,
            )
        }
    }
}
