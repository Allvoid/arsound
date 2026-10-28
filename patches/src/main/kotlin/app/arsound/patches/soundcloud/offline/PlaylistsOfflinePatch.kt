package app.arsound.patches.soundcloud.offline

import app.arsound.patches.soundcloud.misc.hooks.GuardExit
import app.arsound.patches.soundcloud.misc.hooks.hookConstructorEnd
import app.arsound.patches.soundcloud.misc.hooks.insertGuard
import app.arsound.patches.soundcloud.playlist.playlistScreenMethod
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val PLAYLIST_PRELOADER_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/offline/PlaylistPreloader;"

private const val FETCH_AND_SYNC_DESCRIPTOR =
    "Lcom/soundcloud/android/playlists/DataSourceProvider\$fetchAndSyncPlaylistOrFallbackToLocal\$1;"

/**
 * Runs once the stored playlist with its tracks is found. For playlists of other users it
 * requests the server copy and waits for it before the screen gets any data.
 */
private val BytecodePatchContext.fetchAndSyncPlaylistMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass(FETCH_AND_SYNC_DESCRIPTOR)
}

/** The library playlists source, captured to preload their contents. */
private val BytecodePatchContext.myPlaylistOperationsConstructorMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/collections/data/MyPlaylistOperations;")
}

/**
 * The playlist screen streams: its track list waits for a full playlist sync when the last sync is older than
 * 24 hours. The sync is detached so the list does not wait for it.
 */
private fun MutableMethod.detachPlaylistSync() {
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
            invoke-static { v$waitRegister }, $OFFLINE_FIRST_DESCRIPTOR->detachPlaylistSync(Ljava/lang/Object;)Z
            move-result v9
            if-eqz v9, :wait
            sget-object v$waitRegister, Lio/reactivex/rxjava3/internal/operators/completable/CompletableEmpty;->a:Lio/reactivex/rxjava3/internal/operators/completable/CompletableEmpty;
        """,
        ExternalLabel("wait", getInstruction(gotoIndex)),
    )
}

/**
 * The playlist screen streams: the creator follow status waits for the followings sync. It starts with "unknown",
 * so the screen does not wait for the sync. Runs after [detachPlaylistSync], which moves the instructions it counts from.
 */
private fun MutableMethod.startFollowStatusUnknown(startWithItemName: String) {
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
            invoke-static { }, $OFFLINE_FIRST_DESCRIPTOR->isEnabled()Z
            move-result v$freeRegister
            if-eqz v$freeRegister, :keep
            sget-object v$freeRegister, Lcom/soundcloud/android/playlists/PlaylistDetailsMetadata${'$'}FollowStatus;->a:Lcom/soundcloud/android/playlists/PlaylistDetailsMetadata${'$'}FollowStatus;
            invoke-virtual { v$statusRegister, v$freeRegister }, Lio/reactivex/rxjava3/core/Observable;->$startWithItemName(Ljava/lang/Object;)Lio/reactivex/rxjava3/core/Observable;
            move-result-object v$statusRegister
        """,
        ExternalLabel("keep", getInstruction(resultIndex + 1)),
    )
}

/** Stored playlists open at once; the server copy is requested in the background. */
internal val playlistsOfflinePatch = bytecodePatch {
    apply {
        // Save every library playlist's contents ahead of time, as text in SoundCloud's database.
        myPlaylistOperationsConstructorMethod.hookConstructorEnd(
            "$PLAYLIST_PRELOADER_DESCRIPTOR->setMyPlaylistOperations(Ljava/lang/Object;)V",
        )

        fetchAndSyncPlaylistMethod.insertGuard(
            "invoke-static { }, $OFFLINE_FIRST_DESCRIPTOR->isEnabled()Z",
            GuardExit.Custom(
                "move-result",
                """
                    iget-object v0, p0, $FETCH_AND_SYNC_DESCRIPTOR->a:Lcom/soundcloud/android/playlists/DataSourceProvider;
                    iget-object v0, v0, Lcom/soundcloud/android/playlists/DataSourceProvider;->c:Lcom/soundcloud/android/foundation/domain/playlists/PlaylistWithTracksRepository;
                    iget-object v1, p0, $FETCH_AND_SYNC_DESCRIPTOR->c:Lcom/soundcloud/android/foundation/domain/Urn;
                    invoke-static { v0, v1 }, $OFFLINE_FIRST_DESCRIPTOR->syncInBackground(Ljava/lang/Object;Ljava/lang/Object;)V
                    iget-object v0, p0, $FETCH_AND_SYNC_DESCRIPTOR->d:Lcom/soundcloud/android/foundation/domain/repository/SingleItemResponse;
                    invoke-static { v0 }, Lio/reactivex/rxjava3/core/Single;->n(Ljava/lang/Object;)Lio/reactivex/rxjava3/internal/operators/single/SingleJust;
                    move-result-object v0
                    return-object v0
                """,
            ),
        )

        // Observable.startWithItem(item): R8 renamed it and the name changes between versions. It is the
        // only instance method taking one Object and returning an Observable.
        val startWithItemName = firstClassDef("Lio/reactivex/rxjava3/core/Observable;").methods.single {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Lio/reactivex/rxjava3/core/Observable;" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
        }.name
        playlistScreenMethod.apply {
            detachPlaylistSync()
            startFollowStatusUnknown(startWithItemName)
        }
    }
}
