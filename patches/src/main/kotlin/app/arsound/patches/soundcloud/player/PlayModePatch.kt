package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.hooks.GuardExit
import app.arsound.patches.soundcloud.misc.hooks.hookConstructorEnd
import app.arsound.patches.soundcloud.misc.hooks.insertGuard
import app.arsound.patches.soundcloud.misc.hooks.requireResolved
import app.arsound.patches.soundcloud.misc.manifest.addApplicationComponent
import app.arsound.util.ResourceGroup
import app.arsound.util.copyResources
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.arsound.util.indexOfFirstInstructionReversedOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/player/PlayModeButton;"
private const val PACE_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/player/NotificationPace;"
private const val QUEUE_MANAGER_DESCRIPTOR = "Lcom/soundcloud/android/features/playqueue/PlayQueueManager;"

private const val PLAY_QUEUE = "Lcom/soundcloud/android/foundation/playqueue/PlayQueue;"
private const val PLAYBACK_STATE = "Landroid/support/v4/media/session/PlaybackStateCompat;"

/** A method the extension finds by reflection: its class, name and parameter types. */
private class ReflectedMethod(val type: String, val name: String, val parameters: List<String> = emptyList()) {
    override fun toString() = "$type->$name$parameters"
}

/**
 * Methods the extension finds by reflection, beside those looked up below. Their classes are kept by name, the methods
 * may still change with a SoundCloud update.
 */
private val reflectedMethods = listOf(
    ReflectedMethod(PLAY_QUEUE, "shuffle", listOf("I")),
    ReflectedMethod(PLAY_QUEUE, "size"),
    ReflectedMethod(PLAY_QUEUE, "getRepeatMode"),
    ReflectedMethod(PLAY_QUEUE, "items"),
    ReflectedMethod(PLAY_QUEUE, "indexOfPlayQueueItem", listOf("Lcom/soundcloud/android/foundation/playqueue/PlayQueueItem;")),
    ReflectedMethod(PLAY_QUEUE, "addAllPlayQueueItems", listOf("Ljava/util/List;", "Ljava/lang/String;")),
    ReflectedMethod("Lcom/soundcloud/android/foundation/playqueue/PlayQueue\$Shuffled;", "getOriginalQueue"),
    ReflectedMethod(PLAYBACK_STATE, "getCustomActions"),
    ReflectedMethod(PLAYBACK_STATE, "getState"),
    // The id of a custom action: the button and repeated actions are told apart by it.
    ReflectedMethod("Landroid/support/v4/media/session/PlaybackStateCompat\$CustomAction;", "getAction"),
    ReflectedMethod("Landroid/support/v4/media/session/PlaybackStateCompat\$Builder;", "<init>", listOf(PLAYBACK_STATE)),
    ReflectedMethod(
        "Landroid/support/v4/media/session/PlaybackStateCompat\$Builder;",
        "addCustomAction",
        listOf("Ljava/lang/String;", "Ljava/lang/String;", "I"),
    ),
    ReflectedMethod("Landroid/support/v4/media/session/PlaybackStateCompat\$Builder;", "build"),
    ReflectedMethod(
        "Landroidx/core/app/NotificationCompat\$Action;",
        "<init>",
        listOf("I", "Ljava/lang/CharSequence;", "Landroid/app/PendingIntent;"),
    ),
)

/** SoundCloud's play queue holder, a single instance. */
private val BytecodePatchContext.queueManagerConstructorMethod by gettingFirstMethodDeclaratively {
    definingClass(QUEUE_MANAGER_DESCRIPTOR)
    name("<init>")
}

// The extension calls these by name; the patch fails here, not silently in the app, if a SoundCloud update renames
// them. The others it calls are in reflectedMethods above.
private val BytecodePatchContext.queueManagerSetRepeatMethod by gettingFirstMethodDeclaratively {
    definingClass(QUEUE_MANAGER_DESCRIPTOR)
    name("E")
    parameterTypes("Lcom/soundcloud/android/foundation/domain/playqueue/RepeatMode;", "Z")
}

private val BytecodePatchContext.queueManagerSetQueueMethod by gettingFirstMethodDeclaratively {
    definingClass(QUEUE_MANAGER_DESCRIPTOR)
    name("C")
    parameterTypes("Lcom/soundcloud/android/foundation/playqueue/PlayQueue;")
}

private val BytecodePatchContext.queueManagerUnshuffleMethod by gettingFirstMethodDeclaratively {
    definingClass(QUEUE_MANAGER_DESCRIPTOR)
    name("F")
    returnType("V")
    parameterTypes()
}

private val BytecodePatchContext.queueManagerQueueMethod by gettingFirstMethodDeclaratively {
    definingClass(QUEUE_MANAGER_DESCRIPTOR)
    name("k")
    returnType("Lcom/soundcloud/android/foundation/playqueue/PlayQueue;")
    parameterTypes()
}

private val BytecodePatchContext.queueManagerPositionMethod by gettingFirstMethodDeclaratively {
    definingClass(QUEUE_MANAGER_DESCRIPTOR)
    name("i")
    returnType("I")
    parameterTypes()
}

/** Shows the media notification and updates it when playback starts or stops. */
private val BytecodePatchContext.notificationManagerConstructorMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/players/MediaNotificationManager;")
    name("<init>")
}

// Rebuilds the notification; called by name from the extension.
private val BytecodePatchContext.notificationManagerUpdateMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/players/MediaNotificationManager;")
    name("d")
    returnType("V")
    parameterTypes()
}

// Posts the notification afresh and makes the service a foreground one.
private val BytecodePatchContext.notificationManagerStartMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/players/MediaNotificationManager;")
    name("b")
    returnType("V")
    parameterTypes()
}

// Takes the notification away.
private val BytecodePatchContext.notificationManagerStopMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/players/MediaNotificationManager;")
    name("c")
    returnType("V")
    parameterTypes()
}

/** Handles the custom actions of the media session: the like and the end of casting. */
private val BytecodePatchContext.notificationCustomActionMethod by gettingFirstMethodDeclaratively(
    "com.soundcloud.android.playback.action.ACTION_LIKE_TRACK",
    "com.soundcloud.android.playback.action.ACTION_STOP_CAST",
) {
    returnType("V")
    parameterTypes("Ljava/lang/String;")
}

/** Builds the media notification with its actions, which Android 12L and older show. */
private val BytecodePatchContext.playbackNotificationMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/PlaybackNotificationProvider;")
    name("a")
    returnType("Landroid/app/Notification;")
}

private val playModeResourcePatch = resourcePatch {
    apply {
        copyResources(
            "soundcloud/player",
            ResourceGroup(
                "drawable",
                "arsound_play_mode_order.xml",
                "arsound_play_mode_repeat_all.xml",
                "arsound_play_mode_repeat_one.xml",
                "arsound_play_mode_shuffle.xml",
            ),
        )
        document("AndroidManifest.xml").use { document ->
            document.addApplicationComponent(
                "receiver",
                "android:name" to "app.revanced.extension.soundcloud.player.PlayModeReceiver",
                "android:exported" to "false",
            )
        }
    }
}

/**
 * Play mode button: A button in the media notification that switches between playing in order, repeating the queue,
 * repeating the track and shuffling. On Android 12L and older the notification is also rebuilt when the like changes,
 * so its heart follows a like from the notification. Part of the "Arsound" patch, not shown on its own.
 */
val playModePatch = bytecodePatch {
    dependsOn(playModeResourcePatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        // Looked up here only so that a renamed method fails the patch.
        requireResolved(
            queueManagerSetRepeatMethod,
            queueManagerSetQueueMethod,
            queueManagerUnshuffleMethod,
            queueManagerQueueMethod,
            queueManagerPositionMethod,
            notificationManagerUpdateMethod,
        )
        reflectedMethods.forEach { reflected ->
            val found = firstClassDef(reflected.type).methods.any { method ->
                method.name == reflected.name && method.parameterTypes.map { it.toString() } == reflected.parameters
            }
            if (!found) throw PatchException("$reflected not found: the play mode button would not work")
        }

        notificationManagerConstructorMethod.hookConstructorEnd("$EXTENSION_CLASS_DESCRIPTOR->setNotificationManager(Ljava/lang/Object;)V")

        // The updates of the notification are paced under Android's limit of five a second, the last one always made:
        // skipping tracks quickly asked for about sixteen, and the dropped last update left another track shown.
        notificationManagerUpdateMethod.insertGuard(
            "invoke-static/range { p0 .. p0 }, $PACE_CLASS_DESCRIPTOR->defer(Ljava/lang/Object;)Z",
            GuardExit.ReturnVoid,
        )
        notificationManagerStartMethod.addInstructions(0, "invoke-static { }, $PACE_CLASS_DESCRIPTOR->onStart()V")
        notificationManagerStopMethod.addInstructions(0, "invoke-static { }, $PACE_CLASS_DESCRIPTOR->onStop()V")
        queueManagerConstructorMethod.hookConstructorEnd("$EXTENSION_CLASS_DESCRIPTOR->setQueueManager(Ljava/lang/Object;)V")

        // Android 13 and later: a custom action of the playback state. The state goes through the extension where it
        // is handed to the session, after SoundCloud added its like: the like keeps its place, the button comes after.
        mediaServicePlaybackStateMethod.apply {
            val setIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.name == "setPlaybackState"
            }
            val call = getInstruction<FiveRegisterInstruction>(setIndex)
            // Replaced, not preceded: both branches of the like jump to this call.
            replaceInstruction(
                setIndex,
                "invoke-static { v${call.registerC}, v${call.registerD} }, $EXTENSION_CLASS_DESCRIPTOR->" +
                    "setPlaybackState(Ljava/lang/Object;Ljava/lang/Object;)V",
            )
            // The service and the state as SoundCloud made it, to hand it over again after a tap.
            addInstructions(0, "invoke-static { p0, p1 }, $EXTENSION_CLASS_DESCRIPTOR->remember(Ljava/lang/Object;Ljava/lang/Object;)V")
        }

        notificationCustomActionMethod.insertGuard(
            "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS_DESCRIPTOR->onCustomAction(Ljava/lang/String;)Z",
            GuardExit.ReturnVoid,
        )

        // Android 12L and older: an action of the notification itself, after the like.
        playbackNotificationMethod.apply {
            val buildIndex = indexOfFirstInstructionReversedOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.let {
                    it.definingClass == "Landroidx/core/app/NotificationCompat\$Builder;" &&
                        it.returnType == "Landroid/app/Notification;"
                } == true
            }
            val builder = getInstruction<FiveRegisterInstruction>(buildIndex).registerC
            replaceInstruction(
                buildIndex,
                "invoke-static { v$builder }, $EXTENSION_CLASS_DESCRIPTOR->build(Ljava/lang/Object;)Landroid/app/Notification;",
            )
        }
    }
}
