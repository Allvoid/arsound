package app.arsound.patches.soundcloud.local

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
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
        }
    }
}

private val BytecodePatchContext.filterAndSortPlaylistsMethod by gettingFirstMethodDeclaratively {
    name("filterAndSort")
    definingClass("Lcom/soundcloud/android/collections/data/MyPlaylistOperations;")
}

/** Renders a loaded state of the library playlist screens: {@code PlaylistCollectionFragment.P(AsyncLoaderState)}. */
private val BytecodePatchContext.playlistCollectionRenderMethod by gettingFirstMethodDeclaratively {
    name("P")
    definingClass("Lcom/soundcloud/android/features/library/playlists/PlaylistCollectionFragment;")
}

/** onViewCreated of the library playlist screens. */
/** The playlist and album screen, where tracks can be rearranged. */
private val BytecodePatchContext.playlistDetailViewCreatedMethod by gettingFirstMethodDeclaratively {
    name("onViewCreated")
    definingClass("Lcom/soundcloud/android/playlist/view/PlaylistDetailFragment;")
}

private val BytecodePatchContext.playlistCollectionViewCreatedMethod by gettingFirstMethodDeclaratively {
    name("q0")
    definingClass("Lcom/soundcloud/android/features/library/playlists/PlaylistCollectionFragment;")
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
val localMusicPatch = bytecodePatch {
    dependsOn(settingsPatch, downloadTrackPatch, importActivityPatch, trackCellMarksPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
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
