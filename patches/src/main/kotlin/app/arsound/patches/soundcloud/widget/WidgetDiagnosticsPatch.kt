package app.arsound.patches.soundcloud.widget

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType

private const val DIAGNOSTICS = "Lapp/revanced/extension/soundcloud/widget/WidgetDiagnostics;"

/** Wires the buttons of the player widget for the state of the player: pause sends a broadcast, play opens the app. */
private val BytecodePatchContext.wireButtonsMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/widget/PlayerWidgetRemoteViews;")
    name("a")
    returnType("V")
    parameterTypes("Landroid/content/Context;", "Z")
}

/** Receives a tap on pause, previous or next of the widget. */
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
 * Log lines about the player widget on the home screen: the buttons built, a tap that came to the app, what the system
 * tells the widget, and what the phone allows the app in the background. For the log the user sends when a button of the
 * widget does nothing. Part of the "Arsound" patch, not shown on its own.
 */
val widgetDiagnosticsPatch = bytecodePatch {
    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        wireButtonsMethod.addInstructions(0, "invoke-static { p1, p2 }, $DIAGNOSTICS->onButtonsWired(Landroid/content/Context;Z)V")
        tapReceiverMethod.addInstructions(0, "invoke-static { p1, p2 }, $DIAGNOSTICS->onTap(Landroid/content/Context;Landroid/content/Intent;)V")
        providerReceiveMethod.addInstructions(
            0,
            "invoke-static { p1, p2 }, $DIAGNOSTICS->onProviderReceive(Landroid/content/Context;Landroid/content/Intent;)V",
        )
    }
}
