package app.arsound.patches.soundcloud.local

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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val COVERS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/local/LocalCovers;"

/** Reads a local file for the player: title, artist, duration and the picture embedded in it. */
private val BytecodePatchContext.localTrackMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass("Lcom/soundcloud/android/data/track/LocalFileAwareTrackRepository${'$'}track${'$'}2;")
}

/** The playlist picture: the header of the playlist screen and the library cards load it by this address. */
private val BytecodePatchContext.playlistArtworkMethod by gettingFirstMethodDeclaratively {
    name("getArtworkImageUrl")
    definingClass("Lcom/soundcloud/android/foundation/domain/playlists/Playlist;")
}

/** Covers: the stored cover of an imported file, and a picture from the gallery for a playlist. */
internal val localCoversPatch = bytecodePatch {
    apply {
        // Imported files without an embedded picture get their stored cover (one file per album).
        localTrackMethod.apply {
            val pictureIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as ReferenceInstruction).reference.toString().endsWith("->getEmbeddedPicture()[B")
            }
            val pictureRegister = getInstruction<OneRegisterInstruction>(pictureIndex + 1).registerA
            val fileIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as ReferenceInstruction).reference.toString().endsWith("LocalTrackUrn;->getFile()Ljava/io/File;")
            }
            val fileRegister = getInstruction<OneRegisterInstruction>(fileIndex + 1).registerA
            addInstructions(
                pictureIndex + 2,
                """
                    invoke-static { v$pictureRegister, v$fileRegister }, $COVERS_DESCRIPTOR->coverOr([BLjava/io/File;)[B
                    move-result-object v$pictureRegister
                """,
            )

            // Track lists load artwork by URL, so the track also gets the address of its cover file.
            val trackIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_DIRECT_RANGE &&
                    (this as ReferenceInstruction).reference.toString().startsWith("Lcom/soundcloud/android/foundation/domain/tracks/Track;-><init>")
            }
            val trackRegister = getInstruction<RegisterRangeInstruction>(trackIndex).startRegister
            addInstruction(
                trackIndex + 1,
                "invoke-static/range { v$trackRegister .. v$trackRegister }, $COVERS_DESCRIPTOR->addCoverUrl(Ljava/lang/Object;)V",
            )
        }

        // A playlist without SoundCloud tracks can get a picture from the gallery, on this device only.
        // The getter has no spare register, so it is replaced; the extension reads the original field.
        playlistArtworkMethod.addInstructions(
            0,
            """
                invoke-static { p0 }, $COVERS_DESCRIPTOR->playlistArtwork(Ljava/lang/Object;)Ljava/lang/String;
                move-result-object p0
                return-object p0
            """,
        )
    }
}
