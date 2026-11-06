package app.arsound.patches.soundcloud

import app.arsound.patches.all.misc.packagename.changePackageNamePatch
import app.arsound.patches.soundcloud.ads.hidePlanTransitionsPatch
import app.arsound.patches.soundcloud.ads.playbackAdsPatch
import app.arsound.patches.soundcloud.analytics.disableTelemetryPatch
import app.arsound.patches.soundcloud.debug.playbackTimelinePatch
import app.arsound.patches.soundcloud.debug.playerBarLogPatch
import app.arsound.patches.soundcloud.download.downloadButtonsPatch
import app.arsound.patches.soundcloud.download.downloadTrackPatch
import app.arsound.patches.soundcloud.download.headerDownloadButtonPatch
import app.arsound.patches.soundcloud.library.hideImportBannerPatch
import app.arsound.patches.soundcloud.local.localMusicPatch
import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.navbar.bottomTabsPatch
import app.arsound.patches.soundcloud.misc.account.accountTypePatch
import app.arsound.patches.soundcloud.misc.permissions.removePhonePermissionPatch
import app.arsound.patches.soundcloud.misc.playservices.playServicesDialogPatch
import app.arsound.patches.soundcloud.misc.screenshot.screenshotSharePatch
import app.arsound.patches.soundcloud.misc.appname.appNamePatch
import app.arsound.patches.soundcloud.misc.branding.brandingPatch
import app.arsound.patches.soundcloud.misc.translation.russianTranslationPatch
import app.arsound.patches.soundcloud.misc.theme.themePatch
import app.arsound.patches.soundcloud.misc.theme.waveformColourPatch
import app.arsound.patches.soundcloud.recommendations.forYouPatch
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.patches.soundcloud.network.networkPatch
import app.arsound.patches.soundcloud.offline.downloadedPlaybackPatch
import app.arsound.patches.soundcloud.offline.nativeOfflinePatch
import app.arsound.patches.soundcloud.offline.offlinePlaybackGatePatch
import app.arsound.patches.soundcloud.offline.offlineFirstPatch
import app.arsound.patches.soundcloud.power.powerSavingPatch
import app.arsound.patches.soundcloud.player.currentPlayStatePatch
import app.arsound.patches.soundcloud.player.equalizerPatch
import app.arsound.patches.soundcloud.player.listeningStatsPatch
import app.arsound.patches.soundcloud.player.lyricsPatch
import app.arsound.patches.soundcloud.player.mixWithOtherAppsPatch
import app.arsound.patches.soundcloud.player.notificationArtworkPatch
import app.arsound.patches.soundcloud.player.playModePatch
import app.arsound.patches.soundcloud.player.playerCommentsPatch
import app.arsound.patches.soundcloud.player.progressRingPatch
import app.arsound.patches.soundcloud.player.smoothSheetPatch
import app.arsound.patches.soundcloud.player.streamCachePatch
import app.arsound.patches.soundcloud.player.trackTransitionsPatch
import app.arsound.patches.soundcloud.player.vivoMusicCardPatch
import app.arsound.patches.soundcloud.player.wholeCoverPatch
import app.arsound.patches.soundcloud.recommendations.duplicateFilterPatch
import app.arsound.patches.soundcloud.region.regionHintPatch
import app.arsound.patches.soundcloud.upsell.hideSubscriptionOffersPatch
import app.arsound.patches.soundcloud.widget.likesWidgetPatch
import app.arsound.patches.soundcloud.widget.playerWidgetHeartPatch
import app.arsound.patches.soundcloud.widget.widgetDiagnosticsPatch
import app.arsound.patches.soundcloud.widget.squarePlayerWidgetPatch
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

/** What every group needs: the Arsound settings screen, the icon and name, and installing next to the original. */
private val basePatch = bytecodePatch {
    dependsOn(
        settingsPatch,
        accountTypePatch,
        removePhonePermissionPatch,
        playServicesDialogPatch,
        appNamePatch,
        brandingPatch,
        russianTranslationPatch,
        themePatch,
        waveformColourPatch,
        forYouPatch,
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
    playerBarLogPatch,
    playbackTimelinePatch,
    notificationArtworkPatch,
    bottomTabsPatch,
    playModePatch,
    currentPlayStatePatch,
    vivoMusicCardPatch,
    likesWidgetPatch,
    playerWidgetHeartPatch,
    widgetDiagnosticsPatch,
    squarePlayerWidgetPatch,
)

@Suppress("unused")
val arsoundNoAdsGroup = arsoundGroup(
    "Arsound: без лишнего",
    "Без предложений подписки Go и Go+, баннера «Transfer your gems», окна «Поделиться скриншотом» и лишних вставок " +
        "в интерфейсе.",
    playbackAdsPatch,
    hideSubscriptionOffersPatch,
    hidePlanTransitionsPatch,
    hideImportBannerPatch,
    screenshotSharePatch,
)

@Suppress("unused")
val arsoundDownloadsGroup = arsoundGroup(
    "Arsound: скачивание",
    "Скачивание доступных треков в «Музыка/Arsound» или в любую выбранную папку, в том числе на SD-карте, и " +
        "воспроизведение скачанного из файла. Кнопки скачивания SoundCloud скачивают через Arsound.",
    downloadTrackPatch,
    downloadedPlaybackPatch,
    headerDownloadButtonPatch,
    downloadButtonsPatch,
    nativeOfflinePatch,
    offlinePlaybackGatePatch,
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
    "Свой DNS, запрет выхода в сеть с IP выбранных стран, статус сети, вопрос перед проверкой устройства, экономия батареи.",
    networkPatch,
    powerSavingPatch,
    regionHintPatch,
)

@Suppress("unused")
val arsoundRecommendationsGroup = arsoundGroup(
    "Arsound: без дубликатов",
    "Скрывает перезаливы одного и того же трека в рекомендациях и на главной.",
    duplicateFilterPatch,
)

@Suppress("unused")
val arsoundPlayerGroup = arsoundGroup(
    "Arsound: плеер",
    "Эквалайзер, размер и срок хранения кэша потоков, статистика прослушиваний, игра вместе с другими приложениями, " +
        "плавное сворачивание плеера, кольцо прогресса без лишних кадров, тексты песен, плавный переход между треками, загрузка следующего трека заранее, " +
        "скрытие всплывающих комментариев, обложка целиком в полноэкранном плеере.",
    streamCachePatch,
    listeningStatsPatch,
    equalizerPatch,
    mixWithOtherAppsPatch,
    smoothSheetPatch,
    progressRingPatch,
    lyricsPatch,
    trackTransitionsPatch,
    playerCommentsPatch,
    wholeCoverPatch,
)
