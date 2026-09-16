package app.arsound.patches.soundcloud

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
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.bytecodePatch

/*
 * The patches users see. Features are switched in the app (SoundCloud settings → Arsound), so every group is
 * enabled by default and there is nothing to choose while patching.
 *
 * There are several groups instead of one on purpose: ReVanced Manager suggests and downloads the app version
 * compatible with the most visible patches across all patch sources, and the official ReVanced Patches have
 * four SoundCloud patches for an older version. With more Arsound patches, Manager suggests the right version.
 */

private const val SOUNDCLOUD_VERSION = "2026.09.02-release"

/** What every group needs: the Arsound settings screen, the icon and name, and installing next to the original. */
private val basePatch = bytecodePatch {
    dependsOn(
        settingsPatch,
        accountTypePatch,
        appNamePatch,
        brandingPatch,
        // Renames the package after every other patch.
        changePackageNamePatch,
    )
}

private fun arsoundGroup(name: String, description: String, vararg features: Patch) = bytecodePatch(
    name = name,
    description = description,
) {
    dependsOn(basePatch, *features)
    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))
}

@Suppress("unused")
val arsoundBaseGroup = arsoundGroup(
    "Arsound: основа",
    "Меню «Arsound» в настройках SoundCloud, своя иконка, выключенная телеметрия, проверка обновлений. " +
        "Ставится рядом с оригинальным SoundCloud.",
    disableTelemetryPatch,
)

@Suppress("unused")
val arsoundNoAdsGroup = arsoundGroup(
    "Arsound: без рекламы",
    "Нет рекламы между треками, баннеров и полноэкранной рекламы; нет предложений подписки Go и Go+.",
    playbackAdsPatch,
    hideSubscriptionOffersPatch,
)

@Suppress("unused")
val arsoundDownloadsGroup = arsoundGroup(
    "Arsound: скачивание",
    "Скачивание доступных треков в «Музыка/Arsound» и воспроизведение скачанного из файла.",
    downloadTrackPatch,
    downloadedPlaybackPatch,
)

@Suppress("unused")
val arsoundLocalMusicGroup = arsoundGroup(
    "Arsound: своя музыка",
    "Импорт аудиофайлов с телефона, локальные треки в любых плейлистах, свой порядок плейлистов.",
    localMusicPatch,
)

@Suppress("unused")
val arsoundPlaylistsGroup = arsoundGroup(
    "Arsound: мгновенные плейлисты",
    "Плейлисты и альбомы открываются сразу, в том числе без интернета.",
    offlineFirstPatch,
)

@Suppress("unused")
val arsoundNetworkGroup = arsoundGroup(
    "Arsound: сеть и батарея",
    "Свой DNS, запрет выхода в сеть с российского IP, статус сети, вопрос перед проверкой устройства, экономия батареи.",
    networkPatch,
    powerSavingPatch,
)

@Suppress("unused")
val arsoundRecommendationsGroup = arsoundGroup(
    "Arsound: без дубликатов",
    "Скрывает перезаливы одного и того же трека в рекомендациях и на главной.",
    duplicateFilterPatch,
)
