package app.arsound.patches.soundcloud.local

import app.arsound.patches.soundcloud.download.playlistMetaLabelMethod
import app.arsound.patches.soundcloud.misc.hooks.wrapLastReturnedObject
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.arsound.util.indexOfFirstInstructionReversedOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val TRACKS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/local/PlaylistTracks;"

/** Turns a meta line state into its items, for the View and the Compose meta line alike. */
private val BytecodePatchContext.metaLabelItemsMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/ui/utils/MetaLabelUtilsKt;")
}

/**
 * The meta line of the library playlist cells: the number of tracks includes the local additions, and the number of
 * tracks playing from a file follows it.
 */
internal val playlistCellCountPatch = bytecodePatch {
    apply {
        // Library cells: the number of tracks includes the local additions, and the number of tracks
        // playing from a file follows it. The state is tagged with its playlist for the second step; the
        // playlist register gets the state, so the playlist is kept when its tracks are counted.
        // The meta line of a playlist cell is found by its parameters, like the one the download patch hooks.
        playlistMetaLabelMethod.apply {
            val countIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.name == "getTracksCount"
            }
            val countRegister = getInstruction<OneRegisterInstruction>(countIndex + 1).registerA
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
            val stateRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
            addInstruction(
                returnIndex,
                "invoke-static { v$stateRegister }, $TRACKS_DESCRIPTOR->tagCell(Ljava/lang/Object;)V",
            )
            addInstructions(
                countIndex + 2,
                """
                    invoke-static { p0, v$countRegister }, $TRACKS_DESCRIPTOR->cellTracksCount(Ljava/lang/Object;I)I
                    move-result v$countRegister
                """,
            )
        }
        // The state register is reused before the items are returned, so it is kept at the start.
        metaLabelItemsMethod.apply {
            wrapLastReturnedObject("$TRACKS_DESCRIPTOR->withDownloadedCount(Ljava/util/List;)Ljava/util/List;")
            addInstruction(0, "invoke-static { p1 }, $TRACKS_DESCRIPTOR->beforeItems(Ljava/lang/Object;)V")
        }
    }
}
