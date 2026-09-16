package app.arsound.patches.soundcloud

import app.revanced.patcher.patch.bytecodePatch
import app.arsound.patches.all.misc.packagename.changePackageNamePatch
import app.arsound.patches.soundcloud.ads.playbackAdsPatch
import app.arsound.patches.soundcloud.analytics.disableTelemetryPatch
import app.arsound.patches.soundcloud.download.downloadTrackPatch
import app.arsound.patches.soundcloud.local.localMusicPatch
import app.arsound.patches.soundcloud.misc.account.accountTypePatch
import app.arsound.patches.soundcloud.misc.appname.appNamePatch
import app.arsound.patches.soundcloud.misc.branding.brandingPatch
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.patches.soundcloud.network.networkPatch
import app.arsound.patches.soundcloud.offline.downloadedPlaybackPatch
import app.arsound.patches.soundcloud.offline.offlineFirstPatch
import app.arsound.patches.soundcloud.power.powerSavingPatch
import app.arsound.patches.soundcloud.recommendations.duplicateFilterPatch
import app.arsound.patches.soundcloud.upsell.hideSubscriptionOffersPatch

/**
 * The only patch users see: every Arsound feature at once. Features are switched in the app
 * (SoundCloud settings → Arsound), so there is nothing to choose while patching.
 */
@Suppress("unused")
val arsoundPatch = bytecodePatch(
    name = "Arsound",
    description = "All Arsound features: no ads, track downloads, local music, instant playlists, " +
        "network and battery options. Installs next to the original SoundCloud.",
) {
    dependsOn(
        settingsPatch,
        disableTelemetryPatch,
        downloadTrackPatch,
        playbackAdsPatch,
        offlineFirstPatch,
        networkPatch,
        downloadedPlaybackPatch,
        localMusicPatch,
        powerSavingPatch,
        duplicateFilterPatch,
        hideSubscriptionOffersPatch,
        accountTypePatch,
        appNamePatch,
        brandingPatch,
        // Last: renames the package after every other patch.
        changePackageNamePatch,
    )

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))
}
