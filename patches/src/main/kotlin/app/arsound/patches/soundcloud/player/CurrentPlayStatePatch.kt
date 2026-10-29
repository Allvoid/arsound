package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/player/CurrentPlayState;"

private const val PLAY_STATE = "Lcom/soundcloud/android/playback/session/PlayState;"
private const val PLAYER_TRACK_STATE = "Lcom/soundcloud/android/playback/ui/PlayerTrackState;"

/** Takes every state of the media session, position updates included; the media controller's callback calls it. */
private val BytecodePatchContext.sessionStateMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/DefaultPlaySessionStateProvider;")
    name("g")
    returnType("V")
    parameterTypes("Lcom/soundcloud/android/playback/session/PlayStateCompatWrapper;")
}

/** Binds a page of the player to its track's data, the play state among it. */
private val BytecodePatchContext.bindTrackPageMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/ui/TrackPagePresenter;")
    name("i")
    returnType("V")
    parameterTypes("Landroid/view/View;", "Lcom/soundcloud/android/playback/ui/PlayerItem;")
}

// The methods of a play state the extension calls (its stub has them): playing, buffering, and the track. Looked up
// here so that a renamed one fails the patch instead of the player.
private val BytecodePatchContext.playStatePlayingMethod by gettingFirstMethodDeclaratively {
    definingClass(PLAY_STATE)
    name("a")
    returnType("Z")
    parameterTypes()
}

private val BytecodePatchContext.playStateBufferingMethod by gettingFirstMethodDeclaratively {
    definingClass(PLAY_STATE)
    name("h")
    returnType("Z")
    parameterTypes()
}

private val BytecodePatchContext.playStateUrnMethod by gettingFirstMethodDeclaratively {
    definingClass(PLAY_STATE)
    name("o")
    returnType("Lcom/soundcloud/android/foundation/domain/Urn;")
    parameterTypes()
}

/**
 * Smooth waveform after a fast start: a track's page in the player is bound with the play state that is current
 * then, not the one from when the track's data arrived. A track that started playing before its page was bound was
 * shown as buffering to its end, and its waveform jumped twice a second instead of running. Part of the "Arsound"
 * patch, not shown on its own.
 */
val currentPlayStatePatch = bytecodePatch {
    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        // Each lookup throws if its method is gone.
        listOf(playStatePlayingMethod, playStateBufferingMethod, playStateUrnMethod)

        sessionStateMethod.addInstruction(0, "invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->onState($PLAY_STATE)V")

        bindTrackPageMethod.apply {
            val stateIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.IGET_OBJECT &&
                    fieldReference?.let { it.definingClass == PLAYER_TRACK_STATE && it.type == PLAY_STATE } == true
            }
            val stateRegister = getInstruction<OneRegisterInstruction>(stateIndex).registerA
            // The range form: the method has dozens of registers, and a plain invoke-static reaches only sixteen.
            addInstructions(
                stateIndex + 1,
                """
                    invoke-static/range { v$stateRegister .. v$stateRegister }, $EXTENSION_CLASS_DESCRIPTOR->forPage($PLAY_STATE)$PLAY_STATE
                    move-result-object v$stateRegister
                """,
            )
        }
    }
}
