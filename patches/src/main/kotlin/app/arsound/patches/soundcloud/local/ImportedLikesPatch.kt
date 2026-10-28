package app.arsound.patches.soundcloud.local

import app.arsound.patches.soundcloud.misc.hooks.GuardExit
import app.arsound.patches.soundcloud.misc.hooks.hookConstructorEnd
import app.arsound.patches.soundcloud.misc.hooks.insertGuard
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.arsound.util.indexOfFirstInstructionReversedOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val LIKES = "Lapp/revanced/extension/soundcloud/local/ImportedLikes;"
private const val HEARTS = "Lapp/revanced/extension/soundcloud/local/LikeHearts;"

private const val ROOM_LIKES_STORAGE = "Lcom/soundcloud/android/collections/data/likes/RoomLikesReadStorage;"
private const val OBSERVABLE = "Lio/reactivex/rxjava3/core/Observable;"

/**
 * A page of the likes: a join with the tracks table, which has no row for an imported file. Public method of the
 * {@code LikesReadStorage} interface, so its name is not obfuscated.
 */
private val BytecodePatchContext.pagedLikesMethod by gettingFirstMethodDeclaratively {
    name("pagedLiveLoadTrackLikes")
    definingClass(ROOM_LIKES_STORAGE)
}

/** The play queue of the likes (a tap, shuffle, play all): the same join. */
private val BytecodePatchContext.likedPlayItemsMethod by gettingFirstMethodDeclaratively {
    name("loadLikedTrackPlayItems")
    definingClass(ROOM_LIKES_STORAGE)
}

/** Builds the header and the rows of the likes screen from a page; reads the number of likes for the header. */
private val BytecodePatchContext.likesScreenRowsMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/features/library/mytracks/TrackLikesPresenter;")
    returnType(OBSERVABLE)
    parameterTypes("Lcom/soundcloud/android/features/library/mytracks/search/TrackLikesData;", "Z")
}

/** Answers whether a track is liked; every track row asks. It has no spare register. */
private val BytecodePatchContext.isLikedMethod by gettingFirstMethodDeclaratively {
    name("isLiked")
    definingClass("Lcom/soundcloud/android/collections/data/likes/LikedStatuses;")
}

/** What the heart calls: writes the like to the database and sends it to the server. */
private val BytecodePatchContext.toggleTrackLikeMethod by gettingFirstMethodDeclaratively {
    name("toggleTrackLike")
    definingClass("Lcom/soundcloud/android/collections/data/likes/LikeToggler;")
}

/**
 * Runs after every like toggle (chained to its Completable) and sends the liked/unliked analytics events with the
 * track. Public, so the name survives obfuscation.
 */
private val BytecodePatchContext.likeTrackingMethod by gettingFirstMethodDeclaratively {
    name("likeTrackUrn")
    definingClass("Lcom/soundcloud/android/foundation/events/EngagementsTracking;")
    returnType("V")
}

/** Holds the relay of the liked tracks, which the hearts are built from. */
private val BytecodePatchContext.likesStateProviderConstructorMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/collections/data/likes/LikesStateProvider;")
}

/** Asks the server which followed users liked tracks: the urn of an imported file must not be sent. */
private val BytecodePatchContext.usersLikedTracksMethod by gettingFirstMethodDeclaratively {
    name("getUsersLikedTracks")
    definingClass("Lcom/soundcloud/android/likedby/api/RelatedLikersService;")
}

/** Loads the track of an imported file; the likes list asks it to load the files that are not loaded yet. */
private val BytecodePatchContext.trackRepositoryConstructorMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/data/track/LocalFileAwareTrackRepository;")
}

/**
 * Imported files among "Your likes", as if liked, on this phone only: the rows of the likes screen, its play queue
 * and the number in its header, and a filled heart that, when tapped, hides the file from the likes without
 * writing anything. The methods used by the sync with the server and by offline copies are not touched.
 * Part of "Local music".
 */
internal val importedLikesPatch = bytecodePatch {
    dependsOn(settingsPatch)

    apply {
        pagedLikesMethod.insertGuard(
            "invoke-static { p0, p1, p2, p3, p4 }, $LIKES->pagedLikes(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;II)Ljava/lang/Object;",
            GuardExit.ReturnAnswer(castTo = OBSERVABLE),
        )

        likedPlayItemsMethod.insertGuard(
            "invoke-static { p0, p1 }, $LIKES->playItems(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            GuardExit.ReturnAnswer(castTo = "Lio/reactivex/rxjava3/core/Single;"),
        )

        likesScreenRowsMethod.apply {
            val countIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE && methodReference?.name == "loadTotalNumberOfLikes"
            }
            val countRegister = getInstruction<OneRegisterInstruction>(countIndex + 1).registerA
            addInstructions(
                countIndex + 2,
                """
                    invoke-static { v$countRegister }, $LIKES->withImportedCount(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$countRegister
                    check-cast v$countRegister, $OBSERVABLE
                """,
            )
        }

        // .locals 0: the answer is in p0 (the register of "this", no longer needed) just before the return.
        isLikedMethod.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN)
            val answerRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
            require(answerRegister == implementation!!.registerCount - 2) {
                "The answer of LikedStatuses.isLiked is not in p0"
            }
            addInstructions(
                returnIndex,
                """
                    invoke-static { p0, p1 }, $HEARTS->isFilled(ZLjava/lang/Object;)Z
                    move-result p0
                """,
            )
        }

        toggleTrackLikeMethod.insertGuard(
            "invoke-static { p1, p2 }, $HEARTS->toggle(Ljava/lang/Object;Z)Z",
            GuardExit.Custom(
                "move-result",
                """
                    sget-object v0, Lio/reactivex/rxjava3/internal/operators/completable/CompletableEmpty;->a:Lio/reactivex/rxjava3/internal/operators/completable/CompletableEmpty;
                    return-object v0
                """,
            ),
        )

        // The empty Completable above still completes, so SoundCloud's follow-up tracking runs: skip it for files.
        likeTrackingMethod.insertGuard("invoke-static { p1 }, $HEARTS->isLocalTrack(Ljava/lang/Object;)Z", GuardExit.ReturnVoid)

        usersLikedTracksMethod.addInstructions(
            0,
            """
                invoke-static { p1 }, $LIKES->withoutLocalTracks(Ljava/util/List;)Ljava/util/List;
                move-result-object p1
            """,
        )

        likesStateProviderConstructorMethod.hookConstructorEnd("$HEARTS->setStateProvider(Ljava/lang/Object;)V")
        trackRepositoryConstructorMethod.hookConstructorEnd("$LIKES->setTrackRepository(Ljava/lang/Object;)V")
    }
}
