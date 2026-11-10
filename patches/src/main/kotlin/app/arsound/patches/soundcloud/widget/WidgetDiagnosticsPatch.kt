package app.arsound.patches.soundcloud.widget

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.manifest.addApplicationComponent
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.instructions
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType

private const val DIAGNOSTICS = "Lapp/revanced/extension/soundcloud/widget/WidgetDiagnostics;"
private const val BUTTONS = "Lapp/revanced/extension/soundcloud/widget/WidgetButtons;"

/** The invisible screen the widget's buttons open on phones where the launcher's broadcast does not arrive. */
private val widgetRelayResourcePatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { document ->
            document.addApplicationComponent(
                "activity",
                "android:name" to "app.revanced.extension.soundcloud.widget.WidgetRelayActivity",
                "android:exported" to "false",
                "android:theme" to "@android:style/Theme.NoDisplay",
                "android:excludeFromRecents" to "true",
                "android:noHistory" to "true",
                "android:taskAffinity" to "",
            )
        }
    }
}

/** Wires the buttons of the player widget for the state of the player: pause sends a broadcast, play opens the app. */
private val BytecodePatchContext.wireButtonsMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/widget/PlayerWidgetRemoteViews;")
    name("a")
    returnType("V")
    parameterTypes("Landroid/content/Context;", "Z")
}

/** Receives a tap on pause, previous or next of the widget. Its receiver (p0) is handed over to read the player's state afterwards. */
private val BytecodePatchContext.tapReceiverMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/widget/HomescreenWidgetBroadcastReceiver;")
    name("onReceive")
    returnType("V")
    parameterTypes("Landroid/content/Context;", "Landroid/content/Intent;")
}

private val BytecodePatchContext.providerReceiveMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/widget/service/PlayerAppWidgetProvider;")
    name("onReceive")
    returnType("V")
    parameterTypes("Landroid/content/Context;", "Landroid/content/Intent;")
}

/**
 * Log lines about the player widget on the home screen: the buttons built, a tap that came to the app and what the
 * player did with it, what the system tells the widget, why each process started, and what the phone allows the app in
 * the background. They also go to the widget's own log file. For the log the user sends when a button of the widget
 * does nothing. Part of the "Arsound" patch, not shown on its own.
 */
val widgetDiagnosticsPatch = bytecodePatch {
    dependsOn(widgetRelayResourcePatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        // The three buttons that send a broadcast (pause, previous, next) are built by PendingIntent.getBroadcast; each call
        // goes through WidgetButtons.pending, which gives the same PendingIntent unless the phone is one where the launcher's
        // broadcast does not arrive (Xiaomi HyperOS): then the button opens an invisible screen that passes the tap on.
        // Replaced before the log call is added, so the indexes still hold.
        wireButtonsMethod.apply {
            val broadcasts = implementation!!.instructions.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_STATIC &&
                    (instruction as ReferenceInstruction).reference.let {
                        it is MethodReference && it.definingClass == "Landroid/app/PendingIntent;" && it.name == "getBroadcast"
                    }
            }.map { it.index }
            if (broadcasts.size != 3) throw PatchException("The widget builds ${broadcasts.size} broadcast buttons, expected 3")
            for (index in broadcasts) {
                val call = getInstruction<FiveRegisterInstruction>(index)
                replaceInstruction(
                    index,
                    "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE}, v${call.registerF} }, " +
                        "$BUTTONS->pending(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;",
                )
            }
        }
        wireButtonsMethod.addInstructions(0, "invoke-static { p1, p2 }, $DIAGNOSTICS->onButtonsWired(Landroid/content/Context;Z)V")
        tapReceiverMethod.addInstructions(
            0,
            "invoke-static { p0, p1, p2 }, $DIAGNOSTICS->onTap(Landroid/content/BroadcastReceiver;Landroid/content/Context;Landroid/content/Intent;)V",
        )
        providerReceiveMethod.addInstructions(
            0,
            "invoke-static { p1, p2 }, $DIAGNOSTICS->onProviderReceive(Landroid/content/Context;Landroid/content/Intent;)V",
        )
    }
}
