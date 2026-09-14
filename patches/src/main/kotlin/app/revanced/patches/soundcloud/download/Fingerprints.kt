package app.revanced.patches.soundcloud.download

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

internal val BytecodePatchContext.oAuthConstructorMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/api/oauth/OAuth;")
}

/**
 * Receives the data of the track menu and fills its views.
 */
internal val BytecodePatchContext.trackMenuDataConsumerMethod by gettingFirstMethodDeclaratively {
    name("accept")
    definingClass("Lcom/soundcloud/android/features/bottomsheet/track/TrackBottomSheetFragment\$onCreateDialog$1$2;")
}

/**
 * Builds the metadata line of a track cell, including the "downloaded" icon.
 */
internal val BytecodePatchContext.trackMetaLabelMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/uievo/statemappers/MetaLabelsKt;")
    returnType("Lcom/soundcloud/android/ui/components/labels/MetaLabel\$ViewState;")
    instructions(
        method { name == "getOfflineState" && definingClass.endsWith("/TrackItem;") },
    )
}

/**
 * Receives the data of the playlist and album menu and fills its views.
 */
internal val BytecodePatchContext.playlistMenuDataConsumerMethod by gettingFirstMethodDeclaratively {
    name("accept")
    definingClass("Lcom/soundcloud/android/features/bottomsheet/playlist/PlaylistBottomSheetDialogFragment\$onCreateDialog$1$2;")
}
