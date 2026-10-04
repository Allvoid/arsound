package app.arsound.patches.soundcloud.offline

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.arsound.patches.soundcloud.download.downloadTrackPatch
import app.arsound.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/offline/DownloadedPlaybackPatch;"

private const val FUNCTION_CLASS =
    "Lcom/soundcloud/android/playback/PlaybackItemOperations\$playbackItemForTrack\$1;"

/**
 * Turns a loaded track into a playback item. It already plays local files for LocalTrackUrn,
 * with a FileStream that skips stream selection and the network.
 */
private val BytecodePatchContext.playbackItemForTrackMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass(FUNCTION_CLASS)
}

/**
 * Loads the notification artwork of the current track. Playback start waits for this in a zip,
 * so a slow artwork request delayed even tracks played from a file.
 */
private val BytecodePatchContext.notificationArtworkMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass("Lcom/soundcloud/android/playback/mediasession/MetadataOperations\$trackMediaMetadata\$3;")
}

/**
 * Maps player errors to states. A source error while Android reports a connection becomes fatal.
 */
private val BytecodePatchContext.playerStateChangedMethod by gettingFirstMethodDeclaratively {
    name("onPlayerStateChanged")
    definingClass("Lcom/soundcloud/android/exoplayer/BaseExoPlayer\$exoPlayerEventListener\$1;")
}

/**
 * Receives the notification metadata of the current queue item. On the first metadata it plays
 * the current item if the player is playing: this is how a skip starts the next track. Downloaded
 * tracks start before the metadata arrives, so the real metadata restarted them about 1.5 s in.
 */
private val BytecodePatchContext.currentQueueItemMetadataMethod by gettingFirstMethodDeclaratively {
    name("accept")
    definingClass("Lcom/soundcloud/android/playback/players/MediaService${'$'}CurrentQueueItemConsumer;")
}

/**
 * Builds what the player waits for before a queue item starts: the playback item and the
 * notification metadata. For tracks both waited for SoundCloud's repository without a timeout.
 */
private val BytecodePatchContext.playbackDataMethod by gettingFirstMethodDeclaratively {
    name("j")
    definingClass("Lcom/soundcloud/android/playback/PlaybackMediaProvider;")
}

/** Play downloaded files: Plays tracks downloaded by Arsound from the file instead of streaming them. Part of the "Arsound" patch, not shown on its own. */
val downloadedPlaybackPatch = bytecodePatch {
    dependsOn(downloadTrackPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        playbackItemForTrackMethod.apply {
            // v6 holds the Track, v3 is null; everything from here returns, so low registers are free.
            val localCheckIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INSTANCE_OF &&
                    (this as ReferenceInstruction).reference.toString() == "Lcom/soundcloud/android/foundation/domain/LocalTrackUrn;"
            }

            addInstructionsWithLabels(
                localCheckIndex,
                """
                    invoke-static { v6 }, $EXTENSION_CLASS_DESCRIPTOR->playableFilePath(Ljava/lang/Object;)Ljava/lang/String;
                    move-result-object v5
                    if-eqz v5, :stream
                    new-instance v7, Lcom/soundcloud/android/playback/core/stream/Stream${'$'}FileStream;
                    const/16 v4, 0xe
                    invoke-direct { v7, v5, v3, v4 }, Lcom/soundcloud/android/playback/core/stream/Stream${'$'}FileStream;-><init>(Ljava/lang/String;Lcom/soundcloud/android/playback/core/stream/Metadata${'$'}Known;I)V
                    new-instance v4, Lcom/soundcloud/android/playback/core/stream/Stream${'$'}None;
                    invoke-direct { v4 }, Lcom/soundcloud/android/playback/core/stream/Stream${'$'}None;-><init>()V
                    new-instance v5, Lcom/soundcloud/android/playback/core/stream/Streams;
                    invoke-direct { v5, v7, v4 }, Lcom/soundcloud/android/playback/core/stream/Streams;-><init>(Lcom/soundcloud/android/playback/core/stream/Stream;Lcom/soundcloud/android/playback/core/stream/Stream;)V
                    iget-object v7, v0, $FUNCTION_CLASS->b:Lcom/soundcloud/android/foundation/attribution/TrackSourceInfo;
                    iget-wide v8, v0, $FUNCTION_CLASS->c:J
                    invoke-virtual { v6 }, Lcom/soundcloud/android/foundation/domain/tracks/Track;->getTrackUrn()Lcom/soundcloud/android/foundation/domain/TrackUrn;
                    move-result-object v2
                    move-object/from16 v17, v5
                    move-wide/from16 v18, v8
                    const-wide/16 v20, 0x0
                    const/16 v22, 0x0
                    move-object/from16 v23, v7
                    move-object/from16 v24, v2
                    new-instance v16, Lcom/soundcloud/android/playback/AudioPlaybackItem;
                    invoke-direct/range { v16 .. v24 }, Lcom/soundcloud/android/playback/AudioPlaybackItem;-><init>(Lcom/soundcloud/android/playback/core/stream/Streams;JJLcom/soundcloud/android/playback/core/PlaybackItem${'$'}FadeOut;Lcom/soundcloud/android/foundation/attribution/TrackSourceInfo;Lcom/soundcloud/android/foundation/domain/TrackUrn;)V
                    invoke-static/range { v16 .. v16 }, Lio/reactivex/rxjava3/core/Single;->n(Ljava/lang/Object;)Lio/reactivex/rxjava3/internal/operators/single/SingleJust;
                    move-result-object v0
                    invoke-virtual { v0 }, Lio/reactivex/rxjava3/core/Single;->t()Lio/reactivex/rxjava3/core/Maybe;
                    move-result-object v0
                    return-object v0
                """,
                ExternalLabel("stream", getInstruction(localCheckIndex)),
            )
        }

        playbackDataMethod.apply {
            // Track branch: v4 = playbackItemForTrack(...).map(PlaybackItem), v8 TrackSourceInfo,
            // v9/v10 start position, v11 TrackUrn; then goto to AppPlaybackData.
            val itemIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as ReferenceInstruction).reference.toString().startsWith("Lio/reactivex/rxjava3/core/Maybe;->j(")
            }
            val itemRegister = getInstruction<OneRegisterInstruction>(itemIndex + 1).registerA
            addInstructions(
                itemIndex + 2,
                """
                    invoke-static { v$itemRegister, v8, v9, v10, v11 }, Lapp/revanced/extension/soundcloud/offline/InstantFilePlayback;->playbackItem(Ljava/lang/Object;Ljava/lang/Object;JLjava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$itemRegister
                    check-cast v$itemRegister, Lio/reactivex/rxjava3/core/Maybe;
                """,
            )

            // metadata.map(Success) is in v3; v4 is overwritten by the next instruction.
            val metadataIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as ReferenceInstruction).reference.toString().startsWith("Lio/reactivex/rxjava3/core/Observable;->E(")
            }
            val metadataRegister = getInstruction<OneRegisterInstruction>(metadataIndex + 1).registerA
            addInstructions(
                metadataIndex + 2,
                """
                    move-object/from16 v4, p1
                    invoke-static { v$metadataRegister, v4 }, Lapp/revanced/extension/soundcloud/offline/InstantFilePlayback;->metadata(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$metadataRegister
                    check-cast v$metadataRegister, Lio/reactivex/rxjava3/core/Observable;
                """,
            )
        }

        playerStateChangedMethod.apply {
            val connectedIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE &&
                    (this as ReferenceInstruction).reference.toString() ==
                    "Lcom/soundcloud/android/utilities/android/network/ConnectionHelper;->d()Z"
            }
            // iget-object vN, vPlayer, BaseExoPlayer;->c:ConnectionHelper
            val playerRegister = getInstruction<TwoRegisterInstruction>(connectedIndex - 1).registerB
            val connectedRegister = getInstruction<OneRegisterInstruction>(connectedIndex + 1).registerA
            addInstructions(
                connectedIndex + 2,
                """
                    invoke-static { v$playerRegister, v$connectedRegister }, Lapp/revanced/extension/soundcloud/offline/PlaybackRetryPatch;->onPlaybackError(Ljava/lang/Object;Z)Z
                    move-result v$connectedRegister
                """,
            )
        }

        currentQueueItemMetadataMethod.apply {
            // A skip only moves the queue: the next track starts here, on its first metadata, if the
            // player is playing. Downloaded tracks get Arsound's own failure right away, so it counts as
            // that first metadata (same steps as SoundCloud's own code below). Then the real metadata,
            // about 1.5 s later, only updates the notification instead of replaying the track.
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1 }, Lapp/revanced/extension/soundcloud/offline/InstantFilePlayback;->isStartWithoutMetadata(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :original
                    iget-boolean v0, p0, Lcom/soundcloud/android/playback/players/MediaService${'$'}CurrentQueueItemConsumer;->a:Z
                    if-nez v0, :done
                    const/4 v0, 0x1
                    iput-boolean v0, p0, Lcom/soundcloud/android/playback/players/MediaService${'$'}CurrentQueueItemConsumer;->a:Z
                    iget-object v0, p0, Lcom/soundcloud/android/playback/players/MediaService${'$'}CurrentQueueItemConsumer;->b:Lcom/soundcloud/android/playback/players/MediaService;
                    invoke-virtual { v0 }, Lcom/soundcloud/android/playback/players/MediaService;->m()Lcom/soundcloud/android/playback/players/playback/PlaybackManager;
                    move-result-object v0
                    iget-object v1, v0, Lcom/soundcloud/android/playback/players/playback/PlaybackManager;->d:Lcom/soundcloud/android/playback/players/playback/Playback;
                    invoke-interface { v1 }, Lcom/soundcloud/android/playback/players/playback/Playback;->isPlaying()Z
                    move-result v2
                    if-nez v2, :replay
                    invoke-interface { v1 }, Lcom/soundcloud/android/playback/players/playback/Playback;->h()Z
                    move-result v2
                    if-eqz v2, :done
                    :replay
                    const/4 v1, 0x0
                    const/4 v2, 0x0
                    const/4 v3, 0x7
                    invoke-static { v0, v1, v2, v3 }, Lcom/soundcloud/android/playback/players/playback/PlaybackManager;->b(Lcom/soundcloud/android/playback/players/playback/PlaybackManager;ZLjava/lang/Long;I)V
                    :done
                    return-void
                """,
                ExternalLabel("original", getInstruction(0)),
            )
        }

        notificationArtworkMethod.apply {
            // new-instance v2, MaybeSwitchIfEmptySingle; invoke-direct {v2, v0, v1} -- v1 is Single.just(absent).
            val artworkIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.NEW_INSTANCE &&
                    (this as ReferenceInstruction).reference.toString().endsWith("MaybeSwitchIfEmptySingle;")
            }
            addInstructions(
                artworkIndex + 2,
                """
                    move-object v6, v1
                    move-object v1, v2
                    const-wide/16 v2, 0x5dc
                    sget-object v4, Ljava/util/concurrent/TimeUnit;->MILLISECONDS:Ljava/util/concurrent/TimeUnit;
                    iget-object v5, p0, Lcom/soundcloud/android/playback/mediasession/MetadataOperations;->d:Lio/reactivex/rxjava3/core/Scheduler;
                    new-instance v0, Lio/reactivex/rxjava3/internal/operators/single/SingleTimeout;
                    invoke-direct/range { v0 .. v6 }, Lio/reactivex/rxjava3/internal/operators/single/SingleTimeout;-><init>(Lio/reactivex/rxjava3/core/Single;JLjava/util/concurrent/TimeUnit;Lio/reactivex/rxjava3/core/Scheduler;Lio/reactivex/rxjava3/core/SingleSource;)V
                    move-object v2, v0
                """,
            )
        }
    }
}
