package app.arsound.patches.soundcloud.local

import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.method
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val HEADER_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/local/PlaylistHeader;"

/**
 * Binds the header of the playlist screen: hands the Compose view the content drawn from the playlist's
 * metadata. The list binds it again only when the metadata changes, not when Arsound's cover or count does.
 */
private val BytecodePatchContext.playlistDetailsHeaderBindMethod by gettingFirstMethodDeclaratively {
    name("bindItem")
    definingClass(
        "Lcom/soundcloud/android/playlist/view/renderers/PlaylistDetailsSmallerArtworkHeaderRenderer" +
            "${'$'}PlaylistDetailsSmallerArtworkHeaderComposeViewHolder;",
    )
}

/**
 * Draws the header of the playlist screen with Compose: title, author and the meta line with the number of
 * tracks. A lambda with an obfuscated class name, the only one that reads the metadata of the header.
 */
private val BytecodePatchContext.playlistHeaderContentMethod by gettingFirstMethodDeclaratively {
    name("invoke")
    returnType("Ljava/lang/Object;")
    parameterTypes("Ljava/lang/Object;", "Ljava/lang/Object;")
    instructions(
        method { definingClass == "Lcom/soundcloud/android/playlists/PlaylistDetailsMetadataKt;" },
    )
}

/** The header of the playlist screen: redrawn when a cover or count changes, with the number of files in the meta line. */
internal val playlistHeaderPatch = bytecodePatch {
    apply {
        // The header is remembered, so a new cover or count draws it again right away.
        playlistDetailsHeaderBindMethod.addInstruction(
            0,
            "invoke-static { p0, p1 }, $HEADER_DESCRIPTOR->onBind(Ljava/lang/Object;Ljava/lang/Object;)V",
        )

        // The meta line of the header gets the number of tracks that play from a file, as one more part
        // of the text: the parts are joined with " · " into a single line.
        playlistHeaderContentMethod.apply {
            val stateIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC &&
                    methodReference?.definingClass == "Lcom/soundcloud/android/playlists/PlaylistDetailsMetadataKt;"
            }
            val metadataRegister = getInstruction<FiveRegisterInstruction>(stateIndex).registerC
            val joinIndex = indexOfFirstInstructionOrThrow(stateIndex) {
                opcode == Opcode.INVOKE_STATIC_RANGE && methodReference?.let {
                    it.definingClass == "Lkotlin/collections/CollectionsKt;" && it.returnType == "Ljava/lang/String;" &&
                        it.parameterTypes.firstOrNull() == "Ljava/lang/Iterable;"
                } == true
            }
            val partsRegister = getInstruction<RegisterRangeInstruction>(joinIndex).startRegister
            addInstruction(
                joinIndex,
                "invoke-static { v$partsRegister, v$metadataRegister }, " +
                    "$HEADER_DESCRIPTOR->addDownloadedCount(Ljava/util/List;Ljava/lang/Object;)V",
            )
        }
    }
}
