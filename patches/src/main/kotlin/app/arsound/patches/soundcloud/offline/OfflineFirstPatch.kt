package app.arsound.patches.soundcloud.offline

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.patch.bytecodePatch

/** The extension class that decides, for the playlists, users and tracks parts, whether stored data comes first. */
internal const val OFFLINE_FIRST_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/offline/OfflineFirstPatch;"

/**
 * Offline first playlists: Shows stored playlists and albums immediately and refreshes them in the background.
 * Part of the "Arsound" patch, not shown on its own.
 *
 * The work is split by the repository whose answer is changed: the playlists ([playlistsOfflinePatch]), the followings
 * ([followingsOfflinePatch]), the profiles ([usersOfflinePatch]) and the track items ([tracksOfflinePatch]).
 */
val offlineFirstPatch = bytecodePatch {
    dependsOn(settingsPatch, playlistsOfflinePatch, followingsOfflinePatch, usersOfflinePatch, tracksOfflinePatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))
}
