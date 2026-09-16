package app.revanced.patches.soundcloud.upsell

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.returnType
import app.revanced.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.util.getNode
import app.revanced.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/upsell/HidePaywallPatch;"

private const val EMPTY_ACTIVITY_CLASS = "app.revanced.extension.soundcloud.upsell.EmptyActivity"

/**
 * Sets up the UI of the subscription offer screen. Called from onCreate of the base activity.
 */
internal val BytecodePatchContext.paywallSetupUiMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/payments/paywall/SimplePaywallActivity;")
    name("x")
    returnType("V")
    parameterTypes()
}

internal val BytecodePatchContext.destinationPaywallIntentMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/listeners/navigation/DestinationIntents;")
    returnType("Landroid/content/Intent;")
    parameterTypes("Landroid/content/Context;", "Lcom/soundcloud/android/payments/paywall/PaywallNavArgs;", "Landroid/net/Uri;")
}

internal val BytecodePatchContext.factoryPaywallIntentMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/navigation/IntentFactoryImpl;")
    returnType("Landroid/content/Intent;")
    parameterTypes("Landroid/content/Context;")
    name("c")
}

internal val BytecodePatchContext.setupNavigationModelMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/main/MainNavigationView;")
    name("setupNavigationModel")
    returnType("V")
    parameterTypes("Lcom/soundcloud/android/architecture/view/RootActivity;", "Ljava/util/List;")
}

internal val BytecodePatchContext.showInAppMessageMethod by gettingFirstMethodDeclaratively("Failed to show in-app message") {
    definingClass("Lcom/soundcloud/android/moengage/DefaultMoEngageSdk;")
}

internal val BytecodePatchContext.showNudgeMethod by gettingFirstMethodDeclaratively("Failed to show nudge") {
    definingClass("Lcom/soundcloud/android/moengage/DefaultMoEngageSdk;")
}

private val emptyActivityPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { document ->
            document.getNode("application").appendChild(
                document.createElement("activity").apply {
                    setAttribute("android:name", EMPTY_ACTIVITY_CLASS)
                    setAttribute("android:exported", "false")
                    setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                    setAttribute("android:noHistory", "true")
                    setAttribute("android:excludeFromRecents", "true")
                },
            )
        }
    }
}

/** Hide subscription offers: Adds an option to remove the SoundCloud Go and Go+ offer screen and marketing popups. Part of the "Arsound" patch, not shown on its own. */
val hideSubscriptionOffersPatch = bytecodePatch {
    dependsOn(settingsPatch, emptyActivityPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        // Replace the intent to the offer screen before the screen is started, so it never draws.
        fun MutableMethod.filterReturnedIntent() {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
            val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA

            addInstructions(
                returnIndex,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->filterPaywallIntent(Landroid/content/Intent;)Landroid/content/Intent;
                    move-result-object v$register
                """,
            )
        }
        destinationPaywallIntentMethod.filterReturnedIntent()
        factoryPaywallIntentMethod.filterReturnedIntent()

        // Fallback, in case the offer screen is opened some other way.
        paywallSetupUiMethod.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->hidePaywall(Landroid/app/Activity;)Z
                    move-result v0
                    if-eqz v0, :show
                    return-void
                """,
                ExternalLabel("show", getInstruction(0)),
            )
        }

        // Drop the Upgrade tab before the bottom bar menu is built, so the other tabs share its space.
        setupNavigationModelMethod.addInstructions(
            0,
            """
                invoke-static { p2 }, $EXTENSION_CLASS_DESCRIPTOR->filterNavigationTabs(Ljava/util/List;)Ljava/util/List;
                move-result-object p2
            """,
        )

        // MoEngage in-app messages and nudges, the marketing popups shown on app start.
        listOf(showInAppMessageMethod, showNudgeMethod).forEach { method ->
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->hideInAppMessages()Z
                    move-result v0
                    if-eqz v0, :show
                    return-void
                """,
                ExternalLabel("show", method.getInstruction(0)),
            )
        }
    }
}
