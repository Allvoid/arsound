package app.arsound.patches.soundcloud.upsell

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.patch.bytecodePatch

/**
 * Hide subscription offers: Adds an option to remove the SoundCloud Go and Go+ offer screen and marketing popups.
 * Part of the "Arsound" patch, not shown on its own.
 *
 * The work is split by what is hidden: the offer screens ([paywallScreensPatch]), the banners
 * ([upsellBannersPatch]), the Upgrade tab ([navigationTabsPatch]) and the marketing popups ([inAppMessagesPatch]).
 */
val hideSubscriptionOffersPatch = bytecodePatch {
    dependsOn(settingsPatch, paywallScreensPatch, upsellBannersPatch, navigationTabsPatch, inAppMessagesPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))
}
