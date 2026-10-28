package app.arsound.patches.soundcloud.upsell

import app.arsound.patches.soundcloud.misc.hooks.GuardExit
import app.arsound.patches.soundcloud.misc.hooks.insertGuard
import app.arsound.patches.soundcloud.misc.hooks.wrapEveryReturn
import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode

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

/** Decides whether the library may show its own "Get SoundCloud Go+" banner. */
private val BytecodePatchContext.canDisplayUpsellBannerMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/upsell/InlineUpsellOperations;")
    name("canDisplayBanner")
    returnType("Z")
    parameterTypes("Lcom/soundcloud/android/foundation/upsell/UpsellContext;")
}

/** The subscription banners of the home screen and the library. */
internal val upsellBannersPatch = bytecodePatch {
    apply {
        // Returning before the render method starts its Compose group leaves nothing on screen.
        listOf(upsellPlaceholderRenderMethod, bannerAdPlaceholderRenderMethod).forEach { method ->
            method.insertGuard(HIDE_IN_APP_MESSAGES_CALL, GuardExit.ReturnVoid)
        }

        // The library has a banner of its own, next to the server-driven blocks above.
        // The answer is replaced at every exit, reusing the register that already holds it.
        canDisplayUpsellBannerMethod.wrapEveryReturn(
            Opcode.RETURN,
            "$HIDE_PAYWALL_DESCRIPTOR->filterUpsellBanner(Z)Z",
            useRange = true,
        )
    }
}
