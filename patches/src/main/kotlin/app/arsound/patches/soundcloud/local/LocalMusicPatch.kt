package app.arsound.patches.soundcloud.local

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.method
import app.revanced.patcher.instructions
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.returnType
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.methodReference
import app.arsound.util.getNode
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import app.arsound.patches.soundcloud.download.downloadTrackPatch
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.arsound.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/local/LocalMusic;"

private const val ADDITIONS_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/local/LocalAdditions;"

private val BytecodePatchContext.playlistImportSettingsScreenMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/settings/playlistimport/PlaylistImportSettingsScreenKt;")
}

private val importActivityPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { document ->
            document.getNode("application").appendChild(
                document.createElement("activity").apply {
                    setAttribute("android:name", "app.revanced.extension.soundcloud.local.ImportActivity")
                    setAttribute("android:exported", "false")
                    setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                    setAttribute("android:excludeFromRecents", "true")
                },
            )
            // Google's sign-in page for the optional YouTube Music account of the Arsound search.
            document.getNode("application").appendChild(
                document.createElement("activity").apply {
                    setAttribute("android:name", "app.revanced.extension.soundcloud.search.YouTubeLoginActivity")
                    setAttribute("android:exported", "false")
                    setAttribute("android:theme", "@android:style/Theme.Material.NoActionBar")
                },
            )
            // Batch downloads started from a computer over adb; reads lists from the app's own folder only.
            document.getNode("application").appendChild(
                document.createElement("activity").apply {
                    setAttribute("android:name", "app.revanced.extension.soundcloud.local.BatchActivity")
                    setAttribute("android:exported", "true")
                    setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                    setAttribute("android:excludeFromRecents", "true")
                },
            )
        }
    }
}

private val BytecodePatchContext.filterAndSortPlaylistsMethod by gettingFirstMethodDeclaratively {
    name("filterAndSort")
    definingClass("Lcom/soundcloud/android/collections/data/MyPlaylistOperations;")
}

/**
 * Renders a loaded state of the library playlist screens: {@code PlaylistCollectionFragment.R(AsyncLoaderState)}.
 * Found by its parameter: the obfuscated name changes between versions.
 */
private val BytecodePatchContext.playlistCollectionRenderMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/features/library/playlists/PlaylistCollectionFragment;")
    returnType("V")
    parameterTypes("Lcom/soundcloud/android/uniflow/AsyncLoaderState;")
}

/** The playlist and album screen, where tracks can be rearranged. */
private val BytecodePatchContext.playlistDetailViewCreatedMethod by gettingFirstMethodDeclaratively {
    name("onViewCreated")
    definingClass("Lcom/soundcloud/android/playlist/view/PlaylistDetailFragment;")
}

/** onViewCreated of the library playlist screens, under an obfuscated name: found by its parameters. */
private val BytecodePatchContext.playlistCollectionViewCreatedMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/features/library/playlists/PlaylistCollectionFragment;")
    returnType("V")
    parameterTypes("Landroid/view/View;", "Landroid/os/Bundle;")
}

/** Reads the track urns for the playlist screen. */
private val BytecodePatchContext.playlistScreenTracksMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass("Lcom/soundcloud/android/playlists/DataSourceProvider\$liveTracksForPlaylist\$3\$1;")
}

/** Reads the track urns when a whole playlist is played or shuffled. */
private val BytecodePatchContext.trackUrnsForPlaybackMethod by gettingFirstMethodDeclaratively {
    name("trackUrnsForPlayback")
    definingClass("Lcom/soundcloud/android/playlists/DefaultPlaylistOperations;")
}

/** Saves the edited track list of an own playlist to the server. */
private val BytecodePatchContext.editPlaylistTracksMethod by gettingFirstMethodDeclaratively {
    name("editPlaylistTracks")
    definingClass("Lcom/soundcloud/android/playlists/DefaultPlaylistOperations;")
}

/** Track lists; drops urns of local files. */
private val BytecodePatchContext.localFileAwareTracksMethod by gettingFirstMethodDeclaratively {
    name("tracks")
    definingClass("Lcom/soundcloud/android/data/track/LocalFileAwareTrackRepository;")
}

/** Builds the playlist screen for a loaded playlist. */
private val BytecodePatchContext.playlistScreenMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass("Lcom/soundcloud/android/playlists/DataSourceProvider\$playlistWithExtras\$1\$2;")
}

private const val SAVED_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/local/SavedPlaylist;"

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

/** The native "Remove from playlist" action of the track menu. */
private val BytecodePatchContext.removeFromPlaylistMethod by gettingFirstMethodDeclaratively {
    name("removeFromPlaylist")
    definingClass("Lcom/soundcloud/android/libs/engagements/DefaultTrackEngagements;")
}

/** Library playlists list. */
private val BytecodePatchContext.myPlaylistsMethod by gettingFirstMethodDeclaratively {
    name("myPlaylists")
    definingClass("Lcom/soundcloud/android/collections/data/MyPlaylistOperations;")
}

/**
 * The single entry point that turns a list of tracks into a play queue and starts it.
 */
private val BytecodePatchContext.playbackInitiatorConstructorMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/playback/session/PlaybackInitiator;")
}

/** Local music: Adds importing audio files, playing them in the SoundCloud player and adding any track to any playlist on this device only. Part of the "Arsound" patch, not shown on its own. */
/** Reads a local file for the player: title, artist, duration and the picture embedded in it. */
private val BytecodePatchContext.localTrackMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass("Lcom/soundcloud/android/data/track/LocalFileAwareTrackRepository${'$'}track${'$'}2;")
}

/** The playlist picture: the header of the playlist screen and the library cards load it by this address. */
private val BytecodePatchContext.playlistArtworkMethod by gettingFirstMethodDeclaratively {
    name("getArtworkImageUrl")
    definingClass("Lcom/soundcloud/android/foundation/domain/playlists/Playlist;")
}

/**
 * Draws the header of the playlist screen with Compose: title, author and the meta line with the number of
 * tracks. A lambda with an obfuscated class name, the only one that reads the metadata of the header.
 */
private val BytecodePatchContext.playlistHeaderContentMethod by gettingFirstMethodDeclaratively {
    name("invoke")
    returnType("Ljava/lang/Object;")
    parameterTypes("Ljava/lang/Object;", "Ljava/lang/Object;")
    instructions(
        method { definingClass == "Lcom/soundcloud/android/playlists/PlaylistDetailsMetadataKt;" },
    )
}

/**
 * Builds the meta line of a playlist cell: type, number of tracks, download icon. Found by its
 * parameters: the obfuscated name changes between versions.
 */
private val BytecodePatchContext.playlistCellMetaLabelMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/uievo/statemappers/MetaLabelsKt;")
    parameterTypes(
        "Lcom/soundcloud/android/foundation/domain/playlists/Playlist;",
        "Landroid/content/res/Resources;",
        "Z",
        "Lcom/soundcloud/android/foundation/domain/offline/OfflineState;",
    )
}

/** Turns a meta line state into its items, for the View and the Compose meta line alike. */
private val BytecodePatchContext.metaLabelItemsMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/ui/utils/MetaLabelUtilsKt;")
}

/** The stored posts of the user: the own profile lists its playlists from them. */
private val BytecodePatchContext.postsStorageConstructorMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/collections/data/posts/PostsStorage;")
}

private const val TRACKS_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/local/PlaylistTracks;"

val localMusicPatch = bytecodePatch {
    dependsOn(settingsPatch, downloadTrackPatch, importActivityPatch, trackCellMarksPatch)

    compatibleWith("com.soundcloud.android"("2026.10.14-release"))

    apply {
        // Imported files without an embedded picture get their stored cover (one file per album).
        localTrackMethod.apply {
            val pictureIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as ReferenceInstruction).reference.toString().endsWith("->getEmbeddedPicture()[B")
            }
            val pictureRegister = getInstruction<OneRegisterInstruction>(pictureIndex + 1).registerA
            val fileIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as ReferenceInstruction).reference.toString().endsWith("LocalTrackUrn;->getFile()Ljava/io/File;")
            }
            val fileRegister = getInstruction<OneRegisterInstruction>(fileIndex + 1).registerA
            addInstructions(
                pictureIndex + 2,
                """
                    invoke-static { v$pictureRegister, v$fileRegister }, Lapp/revanced/extension/soundcloud/local/LocalCovers;->coverOr([BLjava/io/File;)[B
                    move-result-object v$pictureRegister
                """,
            )

            // Track lists load artwork by URL, so the track also gets the address of its cover file.
            val trackIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_DIRECT_RANGE &&
                    (this as ReferenceInstruction).reference.toString().startsWith("Lcom/soundcloud/android/foundation/domain/tracks/Track;-><init>")
            }
            val trackRegister = getInstruction<RegisterRangeInstruction>(trackIndex).startRegister
            addInstruction(
                trackIndex + 1,
                "invoke-static/range { v$trackRegister .. v$trackRegister }, Lapp/revanced/extension/soundcloud/local/LocalCovers;->addCoverUrl(Ljava/lang/Object;)V",
            )
        }

        // A playlist without SoundCloud tracks can get a picture from the gallery, on this device only.
        // The getter has no spare register, so it is replaced; the extension reads the original field.
        playlistArtworkMethod.addInstructions(
            0,
            """
                invoke-static { p0 }, Lapp/revanced/extension/soundcloud/local/LocalCovers;->playlistArtwork(Ljava/lang/Object;)Ljava/lang/String;
                move-result-object p0
                return-object p0
            """,
        )

        // The meta line of the header gets the number of tracks that play from a file, as one more part
        // of the text: the parts are joined with " · " into a single line.
        playlistHeaderContentMethod.apply {
            val stateIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC &&
                    methodReference?.definingClass == "Lcom/soundcloud/android/playlists/PlaylistDetailsMetadataKt;"
            }
            val metadataRegister = getInstruction<FiveRegisterInstruction>(stateIndex).registerC
            val joinIndex = indexOfFirstInstructionOrThrow(stateIndex) {
                opcode == Opcode.INVOKE_STATIC_RANGE && methodReference?.let {
                    it.definingClass == "Lkotlin/collections/CollectionsKt;" && it.returnType == "Ljava/lang/String;" &&
                        it.parameterTypes.firstOrNull() == "Ljava/lang/Iterable;"
                } == true
            }
            val partsRegister = getInstruction<RegisterRangeInstruction>(joinIndex).startRegister
            addInstruction(
                joinIndex,
                "invoke-static { v$partsRegister, v$metadataRegister }, " +
                    "Lapp/revanced/extension/soundcloud/local/PlaylistHeader;->addDownloadedCount(" +
                    "Ljava/util/List;Ljava/lang/Object;)V",
            )
        }

        // Library cells: the number of tracks includes the local additions, and the number of tracks
        // playing from a file follows it. The state is tagged with its playlist for the second step; the
        // playlist register gets the state, so the playlist is kept when its tracks are counted.
        playlistCellMetaLabelMethod.apply {
            val countIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.name == "getTracksCount"
            }
            val countRegister = getInstruction<OneRegisterInstruction>(countIndex + 1).registerA
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
            val stateRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
            addInstruction(
                returnIndex,
                "invoke-static { v$stateRegister }, $TRACKS_CLASS_DESCRIPTOR->tagCell(Ljava/lang/Object;)V",
            )
            addInstructions(
                countIndex + 2,
                """
                    invoke-static { p0, v$countRegister }, $TRACKS_CLASS_DESCRIPTOR->cellTracksCount(Ljava/lang/Object;I)I
                    move-result v$countRegister
                """,
            )
        }
        // The state register is reused before the items are returned, so it is kept at the start.
        metaLabelItemsMethod.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
            val itemsRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
            addInstructions(
                returnIndex,
                """
                    invoke-static { v$itemsRegister }, $TRACKS_CLASS_DESCRIPTOR->withDownloadedCount(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$itemsRegister
                """,
            )
            addInstruction(0, "invoke-static { p1 }, $TRACKS_CLASS_DESCRIPTOR->beforeItems(Ljava/lang/Object;)V")
        }

        // Manual playlist order: applied after SoundCloud's sorting, rearranged on the library screen.
        filterAndSortPlaylistsMethod.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
            val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA
            addInstructions(
                returnIndex,
                """
                    invoke-static { v$register }, Lapp/revanced/extension/soundcloud/local/PlaylistOrder;->applyOrder(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$register
                """,
            )
        }
        // The screen may show a list kept in memory from before a rearrange: order it again before rendering.
        playlistCollectionRenderMethod.apply {
            // Right after "check-cast p1, List": the null check that follows jumps over anything placed later.
            val castIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.CHECK_CAST && (this as ReferenceInstruction).reference.toString() == "Ljava/util/List;"
            }
            val listRegister = getInstruction<OneRegisterInstruction>(castIndex).registerA
            addInstructions(
                castIndex + 1,
                """
                    invoke-static { v$listRegister }, Lapp/revanced/extension/soundcloud/local/PlaylistOrder;->orderScreenItems(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$listRegister
                """,
            )
        }
        playlistDetailViewCreatedMethod.apply {
            // At the start: the method reuses p1 for other values later. The list is set up after a post.
            addInstruction(
                0,
                "invoke-static { p1 }, Lapp/revanced/extension/soundcloud/local/TrackOrder;->attach(Landroid/view/View;)V",
            )
        }
        playlistCollectionViewCreatedMethod.apply {
            addInstruction(
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                "invoke-static { p0, p1 }, Lapp/revanced/extension/soundcloud/local/PlaylistOrder;->attach(Ljava/lang/Object;Landroid/view/View;)V",
            )
        }

        // "Import files from this phone" below "Manage imported likes" on SoundCloud's "Import my music" screen.
        playlistImportSettingsScreenMethod.apply {
            val revertTitleIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET && fieldReference?.name == "playlist_import_settings_revert_transfer"
            }
            val rowIndex = indexOfFirstInstructionOrThrow(revertTitleIndex) {
                opcode == Opcode.INVOKE_STATIC_RANGE && methodReference?.definingClass?.endsWith("ActionListStandardKt;") == true
            }
            // ActionListStandard(changed, defaults, composer, modifier, title, subtitle, onClick).
            val composerRegister = getInstruction<RegisterRangeInstruction>(rowIndex).startRegister + 2
            addInstruction(
                rowIndex + 1,
                "invoke-static { v$composerRegister }, " +
                    "Lapp/revanced/extension/soundcloud/settings/SettingsEntry;->addImportEntry(Landroidx/compose/runtime/Composer;)V",
            )
        }

        playbackInitiatorConstructorMethod.apply {
            addInstruction(
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->setPlaybackInitiator(Ljava/lang/Object;)V",
            )
        }

        // Only the playlist screen and playback get the additions; editors keep the server track list.
        // Tracks deleted on SoundCloud are kept on the screen, but left out of playback.
        listOf(
            playlistScreenTracksMethod to "appendToTrackUrns",
            trackUrnsForPlaybackMethod to "appendToPlaybackTrackUrns",
        ).forEach { (method, hook) ->
            method.apply {
                val callIndex = indexOfFirstInstructionOrThrow {
                    (this as? ReferenceInstruction)?.reference?.toString()?.contains("->playlistTrackUrns(") == true
                }
                val urnRegister = getInstruction<FiveRegisterInstruction>(callIndex).registerD
                val resultRegister = getInstruction<OneRegisterInstruction>(callIndex + 1).registerA
                addInstructions(
                    callIndex + 2,
                    """
                        invoke-static { v$resultRegister, v$urnRegister }, $ADDITIONS_CLASS_DESCRIPTOR->$hook(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$resultRegister
                        check-cast v$resultRegister, Lio/reactivex/rxjava3/core/Single;
                    """,
                )
            }
        }

        editPlaylistTracksMethod.addInstructions(
            0,
            """
                invoke-static { p1, p2 }, $ADDITIONS_CLASS_DESCRIPTOR->withoutLocalAdditions(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p2
                check-cast p2, Ljava/util/Set;
            """,
        )

        localFileAwareTracksMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0, p1, p2 }, $ADDITIONS_CLASS_DESCRIPTOR->tracksWithLocalFiles(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                if-eqz v0, :original
                check-cast v0, Lio/reactivex/rxjava3/core/Observable;
                return-object v0
            """,
            ExternalLabel("original", localFileAwareTracksMethod.getInstruction(0)),
        )

        postsStorageConstructorMethod.apply {
            addInstruction(
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                "invoke-static { p0 }, $SAVED_CLASS_DESCRIPTOR->setPostsStorage(Ljava/lang/Object;)V",
            )
        }

        playlistOperationsConstructorMethod.apply {
            addInstruction(
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                "invoke-static { p0 }, $SAVED_CLASS_DESCRIPTOR->setPlaylistOperations(Ljava/lang/Object;)V",
            )
        }

        syncIfNotOwnedOrNotFoundMethod.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, Lcom/soundcloud/android/playlists/DataSourceProvider${'$'}syncIfNotOwnedOrNotFound${'$'}1;->b:Lcom/soundcloud/android/foundation/domain/Urn;
                invoke-static { v0, p1 }, $SAVED_CLASS_DESCRIPTOR->useStoredPlaylist(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :original
                invoke-static { p1 }, Lio/reactivex/rxjava3/core/Single;->n(Ljava/lang/Object;)Lio/reactivex/rxjava3/internal/operators/single/SingleJust;
                move-result-object v0
                return-object v0
            """,
            ExternalLabel("original", syncIfNotOwnedOrNotFoundMethod.getInstruction(0)),
        )

        removeFromPlaylistMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { p1, p2 }, $ADDITIONS_CLASS_DESCRIPTOR->removeFromPlaylist(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :original
                return-void
            """,
            ExternalLabel("original", removeFromPlaylistMethod.getInstruction(0)),
        )

        myPlaylistsMethod.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
            val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA
            addInstructions(
                returnIndex,
                """
                    invoke-static { v$register }, $SAVED_CLASS_DESCRIPTOR->hideFromLists(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register
                    check-cast v$register, Lio/reactivex/rxjava3/core/Observable;
                """,
            )
        }

        playlistScreenMethod.apply {
            val itemIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.CHECK_CAST &&
                    (this as ReferenceInstruction).reference.toString() ==
                    "Lcom/soundcloud/android/foundation/domain/playlists/PlaylistItem;"
            }
            val itemRegister = getInstruction<OneRegisterInstruction>(itemIndex).registerA
            addInstruction(
                itemIndex + 1,
                "invoke-static { v$itemRegister }, $ADDITIONS_CLASS_DESCRIPTOR->onPlaylistOpened(Ljava/lang/Object;)V",
            )
        }
    }
}
