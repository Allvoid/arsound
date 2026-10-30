package app.arsound.patches.soundcloud.local

import app.arsound.patches.soundcloud.download.downloadTrackPatch
import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.manifest.addApplicationComponent
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch

private val importActivityPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { document ->
            document.addApplicationComponent(
                "activity",
                "android:name" to "app.revanced.extension.soundcloud.local.ImportActivity",
                "android:exported" to "false",
                "android:theme" to "@android:style/Theme.Translucent.NoTitleBar",
                "android:excludeFromRecents" to "true",
            )
            // Google's sign-in page for the optional YouTube Music account of the Arsound search.
            document.addApplicationComponent(
                "activity",
                "android:name" to "app.revanced.extension.soundcloud.search.YouTubeLoginActivity",
                "android:exported" to "false",
                "android:theme" to "@android:style/Theme.Material.NoActionBar",
                // The WebView handles these itself; a restart would reload the sign-in page and lose what was typed.
                "android:configChanges" to "orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|uiMode|fontScale",
            )
            // Yandex's sign-in page for the optional Yandex account of the transfer from Yandex Music.
            document.addApplicationComponent(
                "activity",
                "android:name" to "app.revanced.extension.soundcloud.transfer.YandexLoginActivity",
                "android:exported" to "false",
                "android:theme" to "@android:style/Theme.Material.NoActionBar",
                "android:configChanges" to "orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|uiMode|fontScale",
            )
            // Batch downloads started from a computer over adb; reads lists from the app's own folder only.
            // Only a sender holding DUMP itself (the adb shell) may start it; the app never starts it.
            document.addApplicationComponent(
                "activity",
                "android:name" to "app.revanced.extension.soundcloud.local.BatchActivity",
                "android:exported" to "true",
                "android:permission" to "android.permission.DUMP",
                "android:theme" to "@android:style/Theme.Translucent.NoTitleBar",
                "android:excludeFromRecents" to "true",
            )
        }
    }
}

/**
 * Local music: Adds importing audio files, playing them in the SoundCloud player and adding any track to any playlist
 * on this device only. Part of the "Arsound" patch, not shown on its own.
 *
 * The hooks are split by the screen or the data they change: the covers ([localCoversPatch]), the playlist header
 * ([playlistHeaderPatch]), the track counts of the library cells ([playlistCellCountPatch]), the manual order
 * ([playlistOrderPatch]), the import row of the settings ([importEntryPatch]), the tracks of playlists and playback
 * ([playlistTracksPatch]) and the playlists saved on this device ([savedPlaylistsPatch]). The track cell marks and the
 * imported likes are in their own files.
 */
val localMusicPatch = bytecodePatch {
    dependsOn(
        settingsPatch,
        downloadTrackPatch,
        importActivityPatch,
        trackCellMarksPatch,
        importedLikesPatch,
        localCoversPatch,
        playlistHeaderPatch,
        playlistCellCountPatch,
        playlistOrderPatch,
        importEntryPatch,
        playlistTracksPatch,
        savedPlaylistsPatch,
    )

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))
}
