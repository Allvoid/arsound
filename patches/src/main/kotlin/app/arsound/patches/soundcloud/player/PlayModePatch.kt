package app.arsound.patches.soundcloud.player

import app.arsound.util.ResourceGroup
import app.arsound.util.copyResources
import app.arsound.util.getNode
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.arsound.util.indexOfFirstInstructionReversedOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/player/PlayModeButton;"
private const val QUEUE_MANAGER = "Lcom/soundcloud/android/features/playqueue/PlayQueueManager;"

/** SoundCloud's play queue holder, a single instance. */
private val BytecodePatchContext.queueManagerConstructorMethod by gettingFirstMethodDeclaratively {
    definingClass(QUEUE_MANAGER)
    name("<init>")
}

// The extension calls these by name; the patch fails here, not silently in the app, if a SoundCloud update renames them.
private val BytecodePatchContext.queueManagerSetRepeatMethod by gettingFirstMethodDeclaratively {
    definingClass(QUEUE_MANAGER)
    name("E")
    parameterTypes("Lcom/soundcloud/android/foundation/domain/playqueue/RepeatMode;", "Z")
}

private val BytecodePatchContext.queueManagerSetQueueMethod by gettingFirstMethodDeclaratively {
    definingClass(QUEUE_MANAGER)
    name("C")
    parameterTypes("Lcom/soundcloud/android/foundation/playqueue/PlayQueue;")
}

private val BytecodePatchContext.queueManagerUnshuffleMethod by gettingFirstMethodDeclaratively {
    definingClass(QUEUE_MANAGER)
    name("F")
    returnType("V")
    parameterTypes()
}

private val BytecodePatchContext.queueManagerQueueMethod by gettingFirstMethodDeclaratively {
    definingClass(QUEUE_MANAGER)
    name("k")
    returnType("Lcom/soundcloud/android/foundation/playqueue/PlayQueue;")
    parameterTypes()
}

private val BytecodePatchContext.queueManagerPositionMethod by gettingFirstMethodDeclaratively {
    definingClass(QUEUE_MANAGER)
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

/** Hands a playback state to the media session and adds the like button to it. */
private val BytecodePatchContext.mediaServicePlaybackStateMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/players/MediaService;")
    name("p")
    returnType("V")
    parameterTypes("Landroid/support/v4/media/session/PlaybackStateCompat;")
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
            document.getNode("application").appendChild(
                document.createElement("receiver").apply {
                    setAttribute("android:name", "app.revanced.extension.soundcloud.player.PlayModeReceiver")
                    setAttribute("android:exported", "false")
                },
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

    compatibleWith("com.soundcloud.android"("2026.10.14-release"))

    apply {
        // Looked up here only so that a renamed method fails the patch.
        listOf(
            queueManagerSetRepeatMethod,
            queueManagerSetQueueMethod,
            queueManagerUnshuffleMethod,
            queueManagerQueueMethod,
            queueManagerPositionMethod,
            notificationManagerUpdateMethod,
        ).forEach { it.name }

        notificationManagerConstructorMethod.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)
            addInstructions(returnIndex, "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->setNotificationManager(Ljava/lang/Object;)V")
        }

        queueManagerConstructorMethod.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)
            addInstructions(returnIndex, "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->setQueueManager(Ljava/lang/Object;)V")
        }

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

        notificationCustomActionMethod.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS_DESCRIPTOR->onCustomAction(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :soundcloud
                    return-void
                """,
                ExternalLabel("soundcloud", getInstruction(0)),
            )
        }

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
