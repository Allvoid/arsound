package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.hooks.insertBeforeLastReturnVoid
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.definingClass
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/player/QueueSwipe;"
private const val QUEUE_MANAGER = "Lcom/soundcloud/android/features/playqueue/PlayQueueManager;"
private const val PLAY_QUEUE = "Lcom/soundcloud/android/foundation/playqueue/PlayQueue;"

/** Binds one track row of the play queue screen to its item: title, artist, cover, tap. */
private val BytecodePatchContext.queueRowBindMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/nextup/TrackPlayQueueItemRenderer\$TrackPlayQueueItemViewHolder;")
    name("bindItem")
    parameterTypes("Ljava/lang/Object;")
}

/** Methods the extension calls by reflection: the patch fails here if an update renames them. */
private val queueMethods = listOf(
    Triple(QUEUE_MANAGER, "p", listOf("Ljava/lang/String;", "[Lcom/soundcloud/android/foundation/domain/TrackUrn;")),
    Triple(QUEUE_MANAGER, "z", listOf("Lcom/soundcloud/android/foundation/playqueue/PlayQueueEvent;")),
    Triple(QUEUE_MANAGER, "i", emptyList()),
    Triple(QUEUE_MANAGER, "k", emptyList()),
    Triple(PLAY_QUEUE, "getEntries", emptyList()),
    Triple(PLAY_QUEUE, "items", emptyList()),
    Triple(PLAY_QUEUE, "indexOfPlayQueueItem", listOf("Lcom/soundcloud/android/foundation/playqueue/PlayQueueItem;")),
)

/**
 * Gestures on the rows of the play queue: a swipe to the left plays the track next (a short swipe) or saves the whole
 * queue as a playlist on the phone (a long one). The swipe to the right stays SoundCloud's (remove from the queue).
 * Part of the "Arsound" patch, not shown on its own.
 */
val queueSwipePatch = bytecodePatch {
    dependsOn(settingsPatch, playModePatch, playNextPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        queueMethods.forEach { (type, name, parameters) ->
            val found = firstClassDef(type).methods.any { method ->
                method.name == name && method.parameterTypes.map { it.toString() } == parameters
            }
            if (!found) throw PatchException("$type->$name$parameters not found: the queue swipe would not work")
        }
        firstClassDef("Lcom/soundcloud/android/foundation/playqueue/PlayQueueEvent\$QueueReordered;")

        // The method has many registers: p0 and p1 are high ones, so they are passed as a range.
        queueRowBindMethod.insertBeforeLastReturnVoid(
            "invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS_DESCRIPTOR->onBind(Ljava/lang/Object;Ljava/lang/Object;)V",
        )
    }
}
