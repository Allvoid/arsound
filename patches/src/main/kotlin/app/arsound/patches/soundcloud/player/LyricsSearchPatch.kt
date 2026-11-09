package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/lyrics/LyricsSearch;"

/** Builds and runs the SQL of the likes search: title or artist LIKE the query (the query is its first parameter). */
private val BytecodePatchContext.likedTracksQueryMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/collections/data/likes/RoomLikesReadStorage;")
    name("getLikedTracks")
}

/**
 * The search of "Your likes" finds a track by a line of its lyrics stored on the phone too: the WHERE of SoundCloud's
 * query gets "or the track is one of these". Part of the "Arsound" patch, not shown on its own.
 */
val lyricsSearchPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        likedTracksQueryMethod.apply {
            val sqlIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.CONST_STRING &&
                    ((this as ReferenceInstruction).reference as StringReference).string.contains("Tracks.title LIKE")
            }
            // The SQL is joined from its parts by one static call; its result is the finished text.
            val joinIndex = indexOfFirstInstructionOrThrow(sqlIndex) { opcode == Opcode.INVOKE_STATIC }
            val resultIndex = joinIndex + 1
            val sqlRegister = getInstruction<OneRegisterInstruction>(resultIndex).registerA
            // p1, the query, is not changed before this point.
            addInstructions(
                resultIndex + 1,
                """
                    invoke-static { v$sqlRegister, p1 }, $EXTENSION_CLASS_DESCRIPTOR->widen(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$sqlRegister
                """,
            )
        }
    }
}
