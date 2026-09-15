package app.revanced.patches.soundcloud.offline

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.soundcloud.download.downloadTrackPatch
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

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

@Suppress("unused")
val downloadedPlaybackPatch = bytecodePatch(
    name = "Play downloaded files",
    description = "Plays tracks downloaded by Arsound from the file instead of streaming them.",
) {
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
    }
}
