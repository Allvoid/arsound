package app.arsound.patches.soundcloud.upsell

import app.arsound.patches.soundcloud.misc.hooks.GuardExit
import app.arsound.patches.soundcloud.misc.hooks.insertGuard
import app.arsound.patches.soundcloud.misc.hooks.wrapLastReturnedObject
import app.arsound.patches.soundcloud.misc.manifest.addApplicationComponent
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.w3c.dom.Element

private const val EMPTY_ACTIVITY_CLASS = "app.revanced.extension.soundcloud.upsell.EmptyActivity"

/**
 * Sets up the UI of the subscription offer screen. Called from onCreate of the base activity.
 */
private val BytecodePatchContext.paywallSetupUiMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/payments/paywall/SimplePaywallActivity;")
    name("x")
    returnType("V")
    parameterTypes()
}

/**
 * The subscription shop, drawn by the Upgrade tab. It sells only through Google Play, so in the mod it always
 * ends in "Oops"; the tab explains that instead.
 */
private val BytecodePatchContext.paywallFragmentCreateViewMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/payments/paywall/SimplePaywallFragment;")
    name("onCreateView")
    returnType("Landroid/view/View;")
}

private val BytecodePatchContext.paywallFragmentViewCreatedMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/payments/paywall/SimplePaywallFragment;")
    name("onViewCreated")
    returnType("V")
}

private val BytecodePatchContext.destinationPaywallIntentMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/listeners/navigation/DestinationIntents;")
    returnType("Landroid/content/Intent;")
    parameterTypes("Landroid/content/Context;", "Lcom/soundcloud/android/payments/paywall/PaywallNavArgs;", "Landroid/net/Uri;")
}

/**
 * Builds the intent of the Go / Go+ plan picker screen. The picker variant of the paywall experiment
 * and the navigation resolver's plan picker link both end here, bypassing the intent builder above.
 */
private val BytecodePatchContext.planPickerIntentMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/listeners/navigation/DestinationIntents;")
    returnType("Landroid/content/Intent;")
    parameterTypes(
        "Landroid/content/Context;",
        "Lcom/soundcloud/android/foundation/actions/models/SelectedPlan;",
        "Lcom/soundcloud/android/foundation/domain/upsell/UpsellData\$PlanBillingCycle;",
        "Ljava/lang/String;",
    )
}

private val BytecodePatchContext.factoryPaywallIntentMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/navigation/IntentFactoryImpl;")
    returnType("Landroid/content/Intent;")
    parameterTypes("Landroid/content/Context;")
    name("c")
}

/**
 * The Downloads row of the library. Without a subscription it opens the offer of one instead of the
 * screen; the offer is replaced by an empty screen, so the row did nothing.
 */
private val BytecodePatchContext.libraryDownloadsClickMethod by gettingFirstMethodDeclaratively {
    name("accept")
    definingClass("Lcom/soundcloud/android/features/library/LibraryPresenter${'$'}attachView${'$'}14;")
}

/**
 * The Downloads row of the new library: builds the row's state from the plan. Without a subscription the state is
 * "Upsell", a tap on it opens the offer (replaced by an empty screen), so the row did nothing.
 */
private val BytecodePatchContext.librarySectionsMethod by gettingFirstMethodDeclaratively {
    name("emit")
    definingClass(
        "Lcom/soundcloud/android/features/library/main/presentation/" +
            "LibraryViewModel\$observeLibrarySections\$1\$invokeSuspend\$\$inlined\$map\$1\$2;",
    )
}

private val emptyActivityPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { document ->
            document.addApplicationComponent(
                "activity",
                "android:name" to EMPTY_ACTIVITY_CLASS,
                "android:exported" to "false",
                "android:theme" to "@android:style/Theme.Translucent.NoTitleBar",
                "android:noHistory" to "true",
                "android:excludeFromRecents" to "true",
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
                    as Element
                button.setAttribute("android:layout_width", "0dp")
                button.setAttribute("android:visibility", "gone")
                button.setAttribute("android:text", "")
            }
        }
    }
}

/**
 * The offer screens of the subscription: the intents that open them are replaced before the screen starts, the screen
 * draws nothing if it opens anyway, and the Upgrade tab explains why the shop is empty.
 */
internal val paywallScreensPatch = bytecodePatch {
    dependsOn(emptyActivityPatch)

    apply {
        // Replace the intent to the offer screen before the screen is started, so it never draws.
        val filterPaywallIntent =
            "$HIDE_PAYWALL_DESCRIPTOR->filterPaywallIntent(Landroid/content/Intent;)Landroid/content/Intent;"
        destinationPaywallIntentMethod.wrapLastReturnedObject(filterPaywallIntent)
        // The picker variant of the paywall experiment returns the intent of this builder from the method
        // above before its last return, so that return alone does not cover it.
        planPickerIntentMethod.wrapLastReturnedObject(filterPaywallIntent)
        factoryPaywallIntentMethod.wrapLastReturnedObject(filterPaywallIntent)

        // Fallback, in case the offer screen is opened some other way.
        // The Downloads row opens the downloads screen, as with a subscription.
        libraryDownloadsClickMethod.apply {
            val checkIndex = indexOfFirstInstructionOrThrow {
                (opcode == Opcode.INVOKE_INTERFACE || opcode == Opcode.INVOKE_VIRTUAL) &&
                    (this as ReferenceInstruction).reference.toString().endsWith("->isOfflineContentEnabled()Z")
            }
            val register = getInstruction<OneRegisterInstruction>(checkIndex + 1).registerA
            addInstructions(checkIndex + 2, "const/4 v$register, 0x1")
        }

        // The same in the new library: the row is built as a normal Downloads row, which opens the downloads screen.
        // Both checks of the plan are answered "enabled"; the last one first, so the index of the first stays right.
        librarySectionsMethod.apply {
            val checks = implementation!!.instructions.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_INTERFACE &&
                    (instruction as ReferenceInstruction).reference.toString().endsWith("->isOfflineContentEnabled()Z")
            }.map { it.index }
            require(checks.isNotEmpty()) { "The library rows do not check isOfflineContentEnabled" }
            for (checkIndex in checks.reversed()) {
                val register = getInstruction<OneRegisterInstruction>(checkIndex + 1).registerA
                addInstructions(checkIndex + 2, "const/4 v$register, 0x1")
            }
        }

        paywallSetupUiMethod.insertGuard(
            "invoke-static { p0 }, $HIDE_PAYWALL_DESCRIPTOR->hidePaywall(Landroid/app/Activity;)Z",
            GuardExit.ReturnVoid,
        )

        // The Upgrade tab: an explanation instead of the shop that cannot sell anything here.
        paywallFragmentCreateViewMethod.insertGuard(
            "invoke-static { p1, p2 }, $UPGRADE_TAB_DESCRIPTOR->createView(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;)Landroid/view/View;",
            GuardExit.ReturnAnswer(),
        )
        // Under the explanation the shop must not load its offers: their failure is the "Oops".
        paywallFragmentViewCreatedMethod.apply {
            val superIndex = indexOfFirstInstructionOrThrow(Opcode.INVOKE_SUPER)
            insertGuard(
                "invoke-static { p1 }, $UPGRADE_TAB_DESCRIPTOR->isPage(Landroid/view/View;)Z",
                GuardExit.ReturnVoid,
                index = superIndex + 1,
            )
        }
    }
}
