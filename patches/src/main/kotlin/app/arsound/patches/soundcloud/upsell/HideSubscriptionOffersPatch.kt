package app.arsound.patches.soundcloud.upsell

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.returnType
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.getNode
import app.arsound.util.indexOfFirstInstructionReversedOrThrow
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

/**
 * Called when a bottom bar tab is tapped. Used only to record which position was tapped,
 * to catch the rare case of a tab opening the screen of another one.
 */
internal val BytecodePatchContext.navigationItemSelectedMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/main/MainNavigationView;")
    name("createOnNavigationItemSelectedListener\$lambda\$0")
    returnType("Z")
    parameterTypes(
        "Lcom/soundcloud/android/ui/main/MainNavigationView;",
        "Lcom/soundcloud/android/architecture/view/RootActivity;",
        "Landroid/view/MenuItem;",
    )
}

/** Decides whether the library may show its own "Get SoundCloud Go+" banner. */
internal val BytecodePatchContext.canDisplayUpsellBannerMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/upsell/InlineUpsellOperations;")
    name("canDisplayBanner")
    returnType("Z")
    parameterTypes("Lcom/soundcloud/android/foundation/upsell/UpsellContext;")
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

        // "Get Pro" and "Upgrade" in the title bar. Nothing can be bought in a mod installed outside Google Play,
        // and the buttons do nothing once the paywall is gone.
        listOf(
            "res/layout/upsell_creator_action_bar_title_layout.xml",
            "res/layout/upsell_consumer_action_bar_title_layout.xml",
        ).forEach { layout ->
            document(layout).use { document ->
                val root = document.documentElement
                root.setAttribute("android:layout_width", "0dp")
                val button = document.getElementsByTagName("com.soundcloud.android.ui.components.text.SoundCloudTextView").item(0)
                    as org.w3c.dom.Element
                button.setAttribute("android:layout_width", "0dp")
                button.setAttribute("android:visibility", "gone")
                button.setAttribute("android:text", "")
            }
        }
    }
}

/** Server-driven home blocks: a subscription banner and a banner ad slot. Their render methods draw nothing when hidden. */
private val BytecodePatchContext.upsellPlaceholderRenderMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/sdui/components/renderers/UpsellPlaceholderRenderer;")
    name("d")
    parameterTypes("Lcom/soundcloud/android/sdui/components/SDUIView\$UpsellPlaceholder;", "Landroidx/compose/runtime/Composer;", "I")
}

private val BytecodePatchContext.bannerAdPlaceholderRenderMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/sdui/components/renderers/BannerAdPlaceholderRenderer;")
    name("d")
    parameterTypes("Lcom/soundcloud/android/sdui/components/SDUIView\$BannerAdPlaceholder;", "Landroidx/compose/runtime/Composer;", "I")
}

/** Hide subscription offers: Adds an option to remove the SoundCloud Go and Go+ offer screen and marketing popups. Part of the "Arsound" patch, not shown on its own. */
val hideSubscriptionOffersPatch = bytecodePatch {
    dependsOn(settingsPatch, emptyActivityPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        // Returning before the render method starts its Compose group leaves nothing on screen.
        listOf(upsellPlaceholderRenderMethod, bannerAdPlaceholderRenderMethod).forEach { method ->
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->hideInAppMessages()Z
                    move-result v0
                    if-eqz v0, :draw
                    return-void
                """,
                ExternalLabel("draw", method.getInstruction(0)),
            )
        }

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

        // The library has a banner of its own, next to the server-driven blocks above.
        // The answer is replaced at every exit, reusing the register that already holds it.
        canDisplayUpsellBannerMethod.apply {
            instructions
                .withIndex()
                .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN }
                .map { (index, _) -> index }
                .reversed()
                .forEach { returnIndex ->
                    val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA

                    addInstructions(
                        returnIndex,
                        """
                            invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS_DESCRIPTOR->filterUpsellBanner(Z)Z
                            move-result v$register
                        """,
                    )
                }
        }

        // Records every tab tap in the log. The bottom bar addresses a tab by its position in the tab
        // list, so a stale position shows up here as a tap that lands on the wrong screen.
        navigationItemSelectedMethod.addInstructions(
            0,
            """
                invoke-static { p0, p2 }, $EXTENSION_CLASS_DESCRIPTOR->logNavigationTap(Ljava/lang/Object;Landroid/view/MenuItem;)V
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
