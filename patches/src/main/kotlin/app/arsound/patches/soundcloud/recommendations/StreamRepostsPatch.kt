package app.arsound.patches.soundcloud.recommendations

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode

private const val STREAM_REPOSTS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/recommendations/StreamReposts;"

private const val API_COLLECTION = "Lcom/soundcloud/android/foundation/api/ApiCollection;"

/** The reader of the Feed's items from a page of the Feed: it is what the page's tracks, users and playlists are stored from. */
private const val STREAM_ITEM_EXTENSIONS = "Lcom/soundcloud/android/stream/ApiStreamItemExtensions;"

/**
 * Stores a page of the Feed (the stream) that SoundCloud loaded: its tracks, users, playlists and the rows of the Feed
 * itself. Called for every page, the first one and the later ones.
 */
private val BytecodePatchContext.storeStreamPageMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/stream/SoundStreamSyncer;")
    returnType("Lio/reactivex/rxjava3/core/Completable;")
    parameterTypes(API_COLLECTION)
}

/**
 * Hide reposts in Feed: Adds a switch that leaves the reposts out of the Feed tab, so it shows only what the artists
 * posted themselves. Part of the "Arsound" patch, not shown on its own.
 */
val streamRepostsPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        storeStreamPageMethod.apply {
            // The method must still be the one that reads the page's items; if SoundCloud moved that work, stop here
            // rather than filter a method that stores something else.
            indexOfFirstInstructionOrThrow {
                (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) &&
                    methodReference?.toString() == "$STREAM_ITEM_EXTENSIONS->b(Ljava/lang/Iterable;)Ljava/util/ArrayList;"
            }

            // p1 is the page. A high register: the range form reaches it, the result is moved back into it.
            addInstructions(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $STREAM_REPOSTS_DESCRIPTOR->hideReposts(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p1
                    check-cast p1, $API_COLLECTION
                """,
            )
        }
    }
}
