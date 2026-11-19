package app.arsound.patches.soundcloud.offline

import app.arsound.patches.soundcloud.download.downloadTrackPatch
import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val SCREEN_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/download/DownloadsScreen;"

/**
 * "Next" with the first queue item of a repeated queue: the playable one is found by a check that asks for the
 * offline state of the item (see [routeOfflineState]).
 */
private val BytecodePatchContext.firstItemOfRepeatedQueueMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/features/playqueue/PlayQueueManager;")
    name("s")
    parameterTypes("Z")
    returnType("Z")
}

/**
 * The check "can this queue item play now" that "next" (and the end of a track) runs over the items after the
 * current one: a track is playable if it is not blocked and is either downloaded or the phone is online.
 */
private val BytecodePatchContext.playableItemCheckMethod by gettingFirstMethodDeclaratively {
    definingClass("Ljr/a;")
    name("invoke")
}

/** The same check, run backwards from the current item for the "previous" button. */
private val BytecodePatchContext.previousPlayableMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/DefaultPlaySessionController;")
    name("o")
    returnType("Z")
}

/**
 * The offline state of a queue item is asked from {@code OfflineProperties.toOfflineState(urn)}: the urn goes into
 * {@link DownloadsScreen#beforeState}, the state SoundCloud answers comes back through {@code stateOnScreen}, which
 * makes it DOWNLOADED for a track downloaded by Arsound (same as on the Downloads screen).
 */
private fun MutableMethod.routeOfflineState() {
    val stateIndex = indexOfFirstInstructionOrThrow {
        opcode == Opcode.INVOKE_VIRTUAL && methodReference?.name == "toOfflineState"
    }
    val urnRegister = getInstruction<FiveRegisterInstruction>(stateIndex).registerD
    val stateRegister = getInstruction<OneRegisterInstruction>(stateIndex + 1).registerA
    addInstructions(
        stateIndex + 2,
        """
            invoke-static { v$stateRegister }, $SCREEN_DESCRIPTOR->stateOnScreen(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v$stateRegister
            check-cast v$stateRegister, Lcom/soundcloud/android/foundation/domain/offline/OfflineState;
        """,
    )
    // The urn register receives the state, so the urn is kept before the call.
    addInstruction(stateIndex, "invoke-static { v$urnRegister }, $SCREEN_DESCRIPTOR->beforeState(Ljava/lang/Object;)V")
}

/**
 * Next track offline: SoundCloud skips every queue item it does not know as downloaded while the phone is offline
 * (the next playable item is the first one downloaded through SoundCloud's own offline, or the first one online). A
 * track Arsound saved as a file counted as not downloaded, so the notification button and the end of a track jumped
 * over those tracks to one and the same far item. Now such a track counts as downloaded in these checks.
 * Part of "Download tracks", not shown on its own.
 */
internal val offlineQueueSkipPatch = bytecodePatch {
    dependsOn(downloadTrackPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        firstItemOfRepeatedQueueMethod.routeOfflineState()
        playableItemCheckMethod.routeOfflineState()
        previousPlayableMethod.routeOfflineState()
    }
}
