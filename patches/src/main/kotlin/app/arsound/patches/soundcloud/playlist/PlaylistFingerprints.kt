package app.arsound.patches.soundcloud.playlist

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext

/** Builds the playlist screen for a loaded playlist: the streams of its tracks, its follow status and its details. */
internal val BytecodePatchContext.playlistScreenMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass("Lcom/soundcloud/android/playlists/DataSourceProvider\$playlistWithExtras\$1\$2;")
}
