package app.arsound.patches.soundcloud.local

import app.arsound.patches.soundcloud.misc.hooks.GuardExit
import app.arsound.patches.soundcloud.misc.hooks.hookConstructorEnd
import app.arsound.patches.soundcloud.misc.hooks.insertGuard
import app.arsound.patches.soundcloud.playlist.playlistScreenMethod
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val LOCAL_MUSIC_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/local/LocalMusic;"
private const val ADDITIONS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/local/LocalAdditions;"

/** Reads the track urns for the playlist screen. */
private val BytecodePatchContext.playlistScreenTracksMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass("Lcom/soundcloud/android/playlists/DataSourceProvider\$liveTracksForPlaylist\$3\$1;")
}

/** Reads the track urns when a whole playlist is played or shuffled. */
private val BytecodePatchContext.trackUrnsForPlaybackMethod by gettingFirstMethodDeclaratively {
    name("trackUrnsForPlayback")
    definingClass("Lcom/soundcloud/android/playlists/DefaultPlaylistOperations;")
}

/** Saves the edited track list of an own playlist to the server. */
private val BytecodePatchContext.editPlaylistTracksMethod by gettingFirstMethodDeclaratively {
    name("editPlaylistTracks")
    definingClass("Lcom/soundcloud/android/playlists/DefaultPlaylistOperations;")
}

/** Track lists; drops urns of local files. */
private val BytecodePatchContext.localFileAwareTracksMethod by gettingFirstMethodDeclaratively {
    name("tracks")
    definingClass("Lcom/soundcloud/android/data/track/LocalFileAwareTrackRepository;")
}

/** The native "Remove from playlist" action of the track menu. */
private val BytecodePatchContext.removeFromPlaylistMethod by gettingFirstMethodDeclaratively {
    name("removeFromPlaylist")
    definingClass("Lcom/soundcloud/android/libs/engagements/DefaultTrackEngagements;")
}

/**
 * The single entry point that turns a list of tracks into a play queue and starts it.
 */
private val BytecodePatchContext.playbackInitiatorConstructorMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/playback/session/PlaybackInitiator;")
}

/**
 * SoundCloud's periodic database cleanup: deletes every stored track that no playlist, like or history of the server
 * names. The tracks added on this phone are named by none of them.
 */
private val BytecodePatchContext.databaseCleanupMethod by gettingFirstMethodDeclaratively {
    name("doWork")
    definingClass("Lcom/soundcloud/android/periodic/DatabaseCleanupWorker;")
}

/** Local files and tracks added from other places inside the track lists of playlists, and in playback. */
internal val playlistTracksPatch = bytecodePatch {
    apply {
        playbackInitiatorConstructorMethod.hookConstructorEnd(
            "$LOCAL_MUSIC_DESCRIPTOR->setPlaybackInitiator(Ljava/lang/Object;)V",
        )

        // Only the playlist screen and playback get the additions; editors keep the server track list.
        // Tracks deleted on SoundCloud are kept on the screen, but left out of playback.
        listOf(
            playlistScreenTracksMethod to "appendToTrackUrns",
            trackUrnsForPlaybackMethod to "appendToPlaybackTrackUrns",
        ).forEach { (method, hook) ->
            method.apply {
                val callIndex = indexOfFirstInstructionOrThrow {
                    (this as? ReferenceInstruction)?.reference?.toString()?.contains("->playlistTrackUrns(") == true
                }
                val urnRegister = getInstruction<FiveRegisterInstruction>(callIndex).registerD
                val resultRegister = getInstruction<OneRegisterInstruction>(callIndex + 1).registerA
                addInstructions(
                    callIndex + 2,
                    """
                        invoke-static { v$resultRegister, v$urnRegister }, $ADDITIONS_DESCRIPTOR->$hook(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$resultRegister
                        check-cast v$resultRegister, Lio/reactivex/rxjava3/core/Single;
                    """,
                )
            }
        }

        editPlaylistTracksMethod.addInstructions(
            0,
            """
                invoke-static { p1, p2 }, $ADDITIONS_DESCRIPTOR->withoutLocalAdditions(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p2
                check-cast p2, Ljava/util/Set;
            """,
        )

        localFileAwareTracksMethod.insertGuard(
            "invoke-static { p0, p1, p2 }, $ADDITIONS_DESCRIPTOR->tracksWithLocalFiles(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            GuardExit.ReturnAnswer(castTo = "Lio/reactivex/rxjava3/core/Observable;"),
        )

        removeFromPlaylistMethod.insertGuard(
            "invoke-static { p1, p2 }, $ADDITIONS_DESCRIPTOR->removeFromPlaylist(Ljava/lang/Object;Ljava/lang/Object;)Z",
            GuardExit.ReturnVoid,
        )

        // The tracks kept for the playlists of the library get the tracks added on this phone: without them the
        // cleanup deleted their titles and covers, and offline a playlist showed only its tracks stored elsewhere.
        databaseCleanupMethod.apply {
            val callIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE &&
                    (this as ReferenceInstruction).reference.toString().startsWith(
                        "Lcom/soundcloud/android/data/playlist/PlaylistWithTracksStorage;->",
                    ) && reference.toString().endsWith("(Ljava/util/List;)Ljava/util/ArrayList;")
            }
            val keptRegister = getInstruction<OneRegisterInstruction>(callIndex + 1).registerA
            addInstruction(
                callIndex + 2,
                "invoke-static { v$keptRegister }, $ADDITIONS_DESCRIPTOR->keepInCleanup(Ljava/util/List;)V",
            )
        }

        playlistScreenMethod.apply {
            val itemIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.CHECK_CAST &&
                    (this as ReferenceInstruction).reference.toString() ==
                    "Lcom/soundcloud/android/foundation/domain/playlists/PlaylistItem;"
            }
            val itemRegister = getInstruction<OneRegisterInstruction>(itemIndex).registerA
            addInstruction(
                itemIndex + 1,
                "invoke-static { v$itemRegister }, $ADDITIONS_DESCRIPTOR->onPlaylistOpened(Ljava/lang/Object;)V",
            )
        }
    }
}
