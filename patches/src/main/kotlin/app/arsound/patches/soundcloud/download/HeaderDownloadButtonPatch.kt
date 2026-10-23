package app.arsound.patches.soundcloud.download

import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/download/HeaderDownloadButton;"

/** Fills the header of a playlist or album: the play, like, repost, download and menu buttons. */
private val BytecodePatchContext.playlistHeaderBindMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/playlist/view/renderers/PlaylistEngagementsPlayableRenderer;")
}

/** The round Compose download button; only the header of "Your likes" uses it. */
private val BytecodePatchContext.composeDownloadButtonMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/ui/components/compose/buttons/DownloadActionButtonKt;")
}

/**
 * Header download buttons: The round download button of a playlist, an album and "Your likes" checks and downloads
 * the available tracks with Arsound instead of opening the Go+ offer. Part of "Download tracks", not shown on its own.
 */
internal val headerDownloadButtonPatch = bytecodePatch {
    apply {
        playlistHeaderBindMethod.apply {
            val listenerIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as ReferenceInstruction).reference.toString()
                        .endsWith("->setOnDownloadActionClickListener(Landroid/view/View\$OnClickListener;)V")
            }
            // The action bar is the object the listener is set on; the metadata is the last parameter.
            // v0 is free here: the next instruction creates the menu listener in it.
            val actionBarRegister = getInstruction<FiveRegisterInstruction>(listenerIndex).registerC
            addInstructions(
                listenerIndex + 1,
                """
                    move-object/from16 v0, p3
                    invoke-static { v$actionBarRegister, v0 }, $EXTENSION_CLASS_DESCRIPTOR->onPlaylistHeaderBound(Landroid/view/View;Ljava/lang/Object;)V
                """,
            )
        }

        composeDownloadButtonMethod.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS_DESCRIPTOR->likesAction(Lkotlin/jvm/functions/Function0;)Lkotlin/jvm/functions/Function0;
                move-result-object p1
            """,
        )
    }
}
