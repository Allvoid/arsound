package app.arsound.patches.soundcloud.local

import app.arsound.patches.soundcloud.misc.hooks.GuardExit
import app.arsound.patches.soundcloud.misc.hooks.hookConstructorEnd
import app.arsound.patches.soundcloud.misc.hooks.insertGuard
import app.arsound.patches.soundcloud.misc.hooks.wrapLastReturnedObject
import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch

private const val SAVED_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/local/SavedPlaylist;"
private const val LOCAL_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/local/LocalPlaylists;"

/** Creates, edits and deletes playlists of the user. */
private val BytecodePatchContext.playlistOperationsConstructorMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/playlists/DefaultPlaylistOperations;")
}

/** Decides whether the playlist screen can use the stored playlist or must load it from the server. */
private val BytecodePatchContext.syncIfNotOwnedOrNotFoundMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass("Lcom/soundcloud/android/playlists/DataSourceProvider\$syncIfNotOwnedOrNotFound\$1;")
}

/** Library playlists list. */
private val BytecodePatchContext.myPlaylistsMethod by gettingFirstMethodDeclaratively {
    name("myPlaylists")
    definingClass("Lcom/soundcloud/android/collections/data/MyPlaylistOperations;")
}

/** The stored posts of the user: the own profile lists its playlists from them. */
private val BytecodePatchContext.postsStorageConstructorMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/collections/data/posts/PostsStorage;")
}

/** The stored posts the sync compares with the server: it removes every one the server does not list. */
private val BytecodePatchContext.localPlaylistPostsMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/sync/posts/LocalPlaylistPostsLoader;")
}

/** Sends one added or removed track of a playlist to the server, once the database has it. */
private val BytecodePatchContext.playlistChangeRequestMethod by gettingFirstMethodDeclaratively {
    name("c")
    definingClass("Lcom/soundcloud/android/playlists/DefaultPlaylistOperations;")
}

/** "Add music" sends the tracks to the server after the database has them, and closes on its answer. */
private val BytecodePatchContext.addMusicRequestMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/playlist/addMusic/TrackSuggestionsDataSource;")
}

/** Syncs one playlist with the server. */
private val BytecodePatchContext.singlePlaylistSyncMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/sync/playlists/SinglePlaylistSyncer;")
}

/** Playlists saved on this device only: kept out of the server lists and opened from the store. */
internal val savedPlaylistsPatch = bytecodePatch {
    apply {
        postsStorageConstructorMethod.hookConstructorEnd("$SAVED_DESCRIPTOR->setPostsStorage(Ljava/lang/Object;)V")
        playlistOperationsConstructorMethod.hookConstructorEnd("$SAVED_DESCRIPTOR->setPlaylistOperations(Ljava/lang/Object;)V")

        syncIfNotOwnedOrNotFoundMethod.insertGuard(
            "invoke-static { v0, p1 }, $SAVED_DESCRIPTOR->useStoredPlaylist(Ljava/lang/Object;Ljava/lang/Object;)Z",
            GuardExit.Custom(
                "move-result",
                """
                    invoke-static { p1 }, Lio/reactivex/rxjava3/core/Single;->n(Ljava/lang/Object;)Lio/reactivex/rxjava3/internal/operators/single/SingleJust;
                    move-result-object v0
                    return-object v0
                """,
            ),
            prelude = "iget-object v0, p0, Lcom/soundcloud/android/playlists/DataSourceProvider${'$'}syncIfNotOwnedOrNotFound${'$'}1;->b:Lcom/soundcloud/android/foundation/domain/Urn;",
        )

        // A playlist of this phone (LocalPlaylists) never meets the server: the sync of posts and of a playlist leave it alone.
        localPlaylistPostsMethod.wrapLastReturnedObject(
            "$LOCAL_DESCRIPTOR->withoutLocalPosts(Ljava/lang/Object;)Ljava/lang/Object;",
            castTo = "Lio/reactivex/rxjava3/core/Single;",
        )
        playlistChangeRequestMethod.insertGuard(
            "invoke-static { p1 }, $LOCAL_DESCRIPTOR->isLocal(Ljava/lang/Object;)Z",
            GuardExit.ReturnVoid,
        )
        addMusicRequestMethod.insertGuard(
            "invoke-static { p1 }, $LOCAL_DESCRIPTOR->isLocal(Ljava/lang/Object;)Z",
            GuardExit.Custom(
                "move-result",
                "sget-object v0, Lcom/soundcloud/android/playlist/addMusic/AddTracksResponse${'$'}Success;->a:Lcom/soundcloud/android/playlist/addMusic/AddTracksResponse${'$'}Success;\ninvoke-static { v0 }, Lio/reactivex/rxjava3/core/Single;->n(Ljava/lang/Object;)Lio/reactivex/rxjava3/internal/operators/single/SingleJust;\nmove-result-object v0\nreturn-object v0",
            ),
        )
        singlePlaylistSyncMethod.insertGuard(
            "invoke-static { v0 }, $LOCAL_DESCRIPTOR->isLocal(Ljava/lang/Object;)Z",
            GuardExit.Custom("move-result", "sget-object v0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;\nreturn-object v0"),
            prelude = "iget-object v0, p0, Lcom/soundcloud/android/sync/playlists/SinglePlaylistSyncer;->a:Lcom/soundcloud/android/foundation/domain/Urn;",
        )

        myPlaylistsMethod.wrapLastReturnedObject(
            "$SAVED_DESCRIPTOR->hideFromLists(Ljava/lang/Object;)Ljava/lang/Object;",
            castTo = "Lio/reactivex/rxjava3/core/Observable;",
        )
    }
}
