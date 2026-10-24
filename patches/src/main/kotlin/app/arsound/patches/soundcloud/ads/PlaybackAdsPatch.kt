package app.arsound.patches.soundcloud.ads

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
import app.arsound.patches.soundcloud.misc.settings.settingsPatch

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

/**
 * Builds the queue items of a fetched ad: the ads followed by the track they play before, or the track with an
 * "error ad" attached. Every ad insertion goes through it (queue start and mid-queue), and its result replaces the
 * track in the play queue.
 */
private val BytecodePatchContext.adQueueItemsMethod by gettingFirstMethodDeclaratively {
    name("e")
    definingClass("Lcom/soundcloud/android/ads/promoted/PromotedAdsOperations;")
    returnType("Ljava/util/List;")
    parameterTypes(
        "Lcom/soundcloud/android/foundation/playqueue/PlayQueueItem\$Playable\$Track;",
        "Lcom/soundcloud/android/foundation/ads/AdWithPriority;",
        "Lcom/soundcloud/android/foundation/ads/AdPlacement;",
        "Lcom/soundcloud/android/foundation/ads/ApiAdsForTrack;",
        "Lcom/soundcloud/android/foundation/ads/ProgrammaticTrackers;",
        "Ljava/lang/String;",
        "Lcom/soundcloud/android/foundation/ads/TrackType;",
    )
}

/**
 * Decides whether a new play queue starts with an ad (a suspend function returning a boxed Boolean). Unlike the
 * mid-queue ads it does not always ask the "no_audio_ads" feature check above.
 */
private val BytecodePatchContext.shouldInsertQueueStartAdMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/ads/promoted/queuestart/ShouldInsertQueueStartAd;")
    returnType("Ljava/lang/Object;")
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

    compatibleWith("com.soundcloud.android"("2026.10.14-release"))

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

        // Queue start ads: from 24.10.2026 SoundCloud began inserting them into queues of accounts whose mid-queue
        // ads were already stopped above. The ad stream never loaded, so playback stopped on a full screen ad page.
        shouldInsertQueueStartAdMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->isAdsBlocked()Z
                move-result v0
                if-eqz v0, :decide
                sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                return-object v0
            """,
            ExternalLabel("decide", shouldInsertQueueStartAdMethod.getInstruction(0)),
        )
        // Any ad that is fetched anyway is not inserted: the track replaces itself. The method has many registers,
        // so the track is passed with a range call.
        adQueueItemsMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS_DESCRIPTOR->trackWithoutAds(Ljava/lang/Object;)Ljava/util/List;
                move-result-object v0
                if-eqz v0, :build
                return-object v0
            """,
            ExternalLabel("build", adQueueItemsMethod.getInstruction(0)),
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
