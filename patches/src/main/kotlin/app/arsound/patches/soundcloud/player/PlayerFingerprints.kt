package app.arsound.patches.soundcloud.player

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.method
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

internal const val TRACK_PAGE_PRESENTER = "Lcom/soundcloud/android/playback/ui/TrackPagePresenter;"

/** Starts a playback item: {@code BaseExoPlayer.f(PlaybackItem)}. */
internal val BytecodePatchContext.playerPlayMethod by gettingFirstMethodDeclaratively {
    name("f")
    definingClass("Lcom/soundcloud/android/exoplayer/BaseExoPlayer;")
    returnType("V")
    parameterTypes("Lcom/soundcloud/android/playback/core/PlaybackItem;")
}

/** Hands a playback state to the media session; it runs about twice a second while playing. */
internal val BytecodePatchContext.mediaServicePlaybackStateMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/players/MediaService;")
    name("p")
    returnType("V")
    parameterTypes("Landroid/support/v4/media/session/PlaybackStateCompat;")
}

/** Fills a page of the full-screen player with a track. Runs for the previous, the current and the next page. */
internal val BytecodePatchContext.bindPlayerPageMethod by gettingFirstMethodDeclaratively {
    definingClass(TRACK_PAGE_PRESENTER)
    name("i")
    returnType("V")
    parameterTypes("Landroid/view/View;", "Lcom/soundcloud/android/playback/ui/PlayerItem;")
}

/**
 * Moves one player page to the offset of the sheet's slide: 0 collapsed, 1 open. It creates the mini player's corner
 * views, which is how it is told apart.
 */
internal val BytecodePatchContext.slidePlayerPageMethod by gettingFirstMethodDeclaratively {
    definingClass(TRACK_PAGE_PRESENTER)
    name("l")
    returnType("V")
    parameterTypes("Landroid/view/View;", "F")
    instructions(
        method { definingClass == "Lcom/soundcloud/android/playback/ui/ArtworkView;" && name == "<init>" },
    )
}
