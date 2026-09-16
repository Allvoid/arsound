package app.revanced.patches.soundcloud.ads

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.revanced.patches.soundcloud.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/ads/PlaybackAdPatch;"

/**
 * The controller receives every reason for requesting a queue-start or mid-queue advertisement.
 * Guarding this point avoids both the HTTP request and insertion of an ad item into the play queue.
 */
private val BytecodePatchContext.playbackAdRequestMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ads/promoted/PromotedPlayerAdsController;")
    returnType("V")
    parameterTypes("Lcom/soundcloud/android/ads/player/PlayerAdsController\$AdFetchReason;")
}

/**
 * The single "no_audio_ads" feature check behind queue-start ads, mid-queue ads and display ad SDK start-up.
 */
private val BytecodePatchContext.shouldRequestAdsMethod by gettingFirstMethodDeclaratively {
    name("getShouldRequestAds")
    definingClass("Lcom/soundcloud/android/configuration/features/DefaultFeatureOperations;")
}

private const val BANNER_CONDITIONS_CLASS =
    "Lcom/soundcloud/android/ads/display/ui/banner/main/BannerAdFetchConditionsImpl;"

// Banner conditions: a - player, b - profile, c - library, d - playlist, e - home and feed.
/** Shows the full screen ad when the main screen opens (a suspend function). */
private val BytecodePatchContext.interstitialAdShowMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/ads/display/ui/interstitial/DefaultInterstitialAdController;")
}

private val BytecodePatchContext.bannerPlayerMethod by gettingFirstMethodDeclaratively { name("a"); definingClass(BANNER_CONDITIONS_CLASS); returnType("Z") }
private val BytecodePatchContext.bannerProfileMethod by gettingFirstMethodDeclaratively { name("b"); definingClass(BANNER_CONDITIONS_CLASS); returnType("Z") }
private val BytecodePatchContext.bannerLibraryMethod by gettingFirstMethodDeclaratively { name("c"); definingClass(BANNER_CONDITIONS_CLASS); returnType("Z") }
private val BytecodePatchContext.bannerPlaylistMethod by gettingFirstMethodDeclaratively { name("d"); definingClass(BANNER_CONDITIONS_CLASS); returnType("Z") }
private val BytecodePatchContext.bannerSectionsMethod by gettingFirstMethodDeclaratively { name("e"); definingClass(BANNER_CONDITIONS_CLASS); returnType("Z") }

/** Control playback advertisements: Adds an option to prevent SoundCloud from requesting audio, video and banner advertisements. Part of the "Arsound" patch, not shown on its own. */
val playbackAdsPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        // Full screen ad on app start: the call returns right away, as if no ad was available.
        interstitialAdShowMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->isAdsBlocked()Z
                move-result v0
                if-eqz v0, :show
                sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
                return-object v0
            """,
            ExternalLabel("show", interstitialAdShowMethod.getInstruction(0)),
        )

        playbackAdRequestMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->blockAdRequest()Z
                move-result v0
                if-eqz v0, :request
                return-void
            """,
            ExternalLabel("request", playbackAdRequestMethod.getInstruction(0)),
        )

        // Returning false from these methods is what SoundCloud does for accounts without ads,
        // so callers simply skip the banner view or the ad item instead of leaving an empty strip.
        listOf(
            shouldRequestAdsMethod,
            bannerPlayerMethod,
            bannerProfileMethod,
            bannerLibraryMethod,
            bannerPlaylistMethod,
            bannerSectionsMethod,
        ).forEach { method ->
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->isAdsBlocked()Z
                    move-result v0
                    if-eqz v0, :original
                    const/4 v0, 0x0
                    return v0
                """,
                ExternalLabel("original", method.getInstruction(0)),
            )
        }
    }
}
