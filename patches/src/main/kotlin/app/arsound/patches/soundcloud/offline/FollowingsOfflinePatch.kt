package app.arsound.patches.soundcloud.offline

import app.arsound.patches.soundcloud.misc.hooks.wrapLastReturnedObject
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch

private const val FOLLOWINGS_CACHE_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/offline/FollowingsCache;"

private const val OBSERVABLE = "Lio/reactivex/rxjava3/core/Observable;"

/** The Following screen of the library: loads the followed profiles page by page from the server. */
private val BytecodePatchContext.hotPagedFollowingsMethod by gettingFirstMethodDeclaratively {
    name("hotPagedFollowings")
    definingClass("Lcom/soundcloud/android/profile/data/UserProfileOperations;")
}

/** Follow states of profiles. Syncs the followings with the server first, which never ends offline. */
private val BytecodePatchContext.followingStatusesMethod by gettingFirstMethodDeclaratively {
    name("followingStatuses")
    definingClass("Lcom/soundcloud/android/collections/data/followings/FollowingStateProvider;")
}

/** The followed profiles and their follow states come from the device first, the server answer follows. */
internal val followingsOfflinePatch = bytecodePatch {
    apply {
        // Following shows the stored profiles first; the server answer replaces them, and its failure changes nothing.
        hotPagedFollowingsMethod.apply {
            wrapLastReturnedObject(
                "$FOLLOWINGS_CACHE_DESCRIPTOR->wrap(Ljava/lang/Object;)Ljava/lang/Object;",
                castTo = OBSERVABLE,
            )
            // The arguments are overwritten by the end of the method, so they are kept first.
            addInstruction(
                0,
                "invoke-static { p0, p1, p2 }, $FOLLOWINGS_CACHE_DESCRIPTOR->begin(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
            )
        }

        // Stored follow states right away; the ones after the sync follow when it comes.
        followingStatusesMethod.apply {
            wrapLastReturnedObject(
                "$FOLLOWINGS_CACHE_DESCRIPTOR->wrapStatuses(Ljava/lang/Object;)Ljava/lang/Object;",
                castTo = OBSERVABLE,
            )
            addInstruction(0, "invoke-static { p0 }, $FOLLOWINGS_CACHE_DESCRIPTOR->beginStatuses(Ljava/lang/Object;)V")
        }
    }
}
