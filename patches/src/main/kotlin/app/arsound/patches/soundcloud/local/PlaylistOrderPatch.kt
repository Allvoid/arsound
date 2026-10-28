package app.arsound.patches.soundcloud.local

import app.arsound.patches.soundcloud.misc.hooks.insertBeforeLastReturnVoid
import app.arsound.patches.soundcloud.misc.hooks.wrapLastReturnedObject
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val PLAYLIST_ORDER_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/local/PlaylistOrder;"
private const val TRACK_ORDER_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/local/TrackOrder;"

private val BytecodePatchContext.filterAndSortPlaylistsMethod by gettingFirstMethodDeclaratively {
    name("filterAndSort")
    definingClass("Lcom/soundcloud/android/collections/data/MyPlaylistOperations;")
}

/**
 * Renders a loaded state of the library playlist screens: {@code PlaylistCollectionFragment.R(AsyncLoaderState)}.
 * Found by its parameter: the obfuscated name changes between versions.
 */
private val BytecodePatchContext.playlistCollectionRenderMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/features/library/playlists/PlaylistCollectionFragment;")
    returnType("V")
    parameterTypes("Lcom/soundcloud/android/uniflow/AsyncLoaderState;")
}

/** The playlist and album screen, where tracks can be rearranged. */
private val BytecodePatchContext.playlistDetailViewCreatedMethod by gettingFirstMethodDeclaratively {
    name("onViewCreated")
    definingClass("Lcom/soundcloud/android/playlist/view/PlaylistDetailFragment;")
}

/** onViewCreated of the library playlist screens, under an obfuscated name: found by its parameters. */
private val BytecodePatchContext.playlistCollectionViewCreatedMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/features/library/playlists/PlaylistCollectionFragment;")
    returnType("V")
    parameterTypes("Landroid/view/View;", "Landroid/os/Bundle;")
}

/** Own order of the playlists in the library, and of the tracks in a playlist, rearranged by dragging. */
internal val playlistOrderPatch = bytecodePatch {
    apply {
        // Manual playlist order: applied after SoundCloud's sorting, rearranged on the library screen.
        filterAndSortPlaylistsMethod.wrapLastReturnedObject(
            "$PLAYLIST_ORDER_DESCRIPTOR->applyOrder(Ljava/util/List;)Ljava/util/List;",
        )
        // The screen may show a list kept in memory from before a rearrange: order it again before rendering.
        playlistCollectionRenderMethod.apply {
            // Right after "check-cast p1, List": the null check that follows jumps over anything placed later.
            val castIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.CHECK_CAST && (this as ReferenceInstruction).reference.toString() == "Ljava/util/List;"
            }
            val listRegister = getInstruction<OneRegisterInstruction>(castIndex).registerA
            addInstructions(
                castIndex + 1,
                """
                    invoke-static { v$listRegister }, $PLAYLIST_ORDER_DESCRIPTOR->orderScreenItems(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$listRegister
                """,
            )
        }
        // At the start: the method reuses p1 for other values later. The list is set up after a post.
        playlistDetailViewCreatedMethod.addInstruction(
            0,
            "invoke-static { p1 }, $TRACK_ORDER_DESCRIPTOR->attach(Landroid/view/View;)V",
        )
        playlistCollectionViewCreatedMethod.insertBeforeLastReturnVoid(
            "invoke-static { p0, p1 }, $PLAYLIST_ORDER_DESCRIPTOR->attach(Ljava/lang/Object;Landroid/view/View;)V",
        )
    }
}
