/**
 * Local music: imported audio files, local additions to playlists, own ordering, merging, "Imported" playlist, and
 * imported files among "Your likes". Everything here lives on this phone only.
 *
 * <p>User view: "Import my music" copies audio files into the app (files/arsound/imported); they play through the normal
 * SoundCloud player, appear in the "Imported" playlist and in "Your likes", can be added to any playlist (even someone
 * else's) on this device, get covers, can be rearranged by drag (playlists in the library, tracks in a playlist) and
 * merged ("Merge playlists"). A watched folder is imported automatically; a batch list can fill a playlist from a PC.
 *
 * <p>Entry points (Kotlin: {@code local/*Patch.kt}; {@code ImportActivity}, {@code BatchActivity} are declared in the
 * manifest by {@code LocalMusicPatch.kt}; {@code settings.SettingsEntry.addImportEntry} is hooked by
 * {@code ImportEntryPatch.kt}):
 * <ul>
 * <li>{@code LocalMusic.setPlaybackInitiator}; {@code LocalAdditions.tracksWithLocalFiles/withoutLocalAdditions/
 * onPlaylistOpened/removeFromPlaylist/keepInCleanup} (the last one in SoundCloud's database cleanup) and the menu rows (also called from {@code download/DownloadTrackPatch.java});
 * {@code PlaylistTracks.beforeItems/cellTracksCount/tagCell/withDownloadedCount}; {@code PlaylistHeader.onBind/
 * addDownloadedCount}; {@code SavedPlaylist.hideFromLists/setPlaylistOperations/setPostsStorage/useStoredPlaylist}.</li>
 * <li>{@code ImportedLikes.pagedLikes/playItems/withImportedCount/withoutLocalTracks/setTrackRepository} and
 * {@code LikeHearts.isFilled/isLocalTrack/setStateProvider/toggle}; {@code LocalCovers.addCoverUrl/coverOr/
 * playlistArtwork}; {@code PlaylistOrder.applyOrder/attach/orderScreenItems}, {@code TrackOrder.attach};
 * {@code TrackCellMarks.addImportedIcon/cellModifier/onTap}.</li>
 * <li>Screen hook (no patch, in {@code update.UpdateChecker}): {@code WatchFolder.onActivityResumed}.</li>
 * </ul>
 *
 * <p>Main classes (thin facades first, work in the classes beside them): {@code LocalMusic} (import and play),
 * {@code LocalAdditions} (-> {@code AdditionsStore}, {@code LocalTrackLoader}, {@code LocalFileTracks},
 * {@code AdditionsMenus}; entries are {@code Entry} = {@code FileEntry} or {@code TrackEntry}), {@code ImportedLikes}
 * (-> {@code LikesFeed}, {@code LikesPaging}, {@code HiddenLikes}, {@code LikeObjects}), {@code LocalCovers}
 * (-> {@code CoverImages}), {@code PlaylistMerge} (-> {@code MergePicker}, {@code MergeJob}, {@code MergePlan},
 * {@code MergeReport}), {@code DragReorder} with {@code ManualOrder}, {@code RemovedTracks} (tracks SoundCloud deleted
 * keep their place), {@code SavedPlaylist}/{@code SavedPlaylistSync}, {@code BatchRunner}/{@code TrackFinder}.
 *
 * <p>Settings ({@code settings.Settings}): {@code imported_in_likes}, {@code saved_playlist},
 * {@code saved_playlist_hidden}, {@code playlist_order}. Own preference files: {@code arsound_local_additions},
 * {@code arsound_local_covers}, {@code arsound_playlist_tracks}, {@code arsound_removed_tracks},
 * {@code arsound_watch_folder}. Screen: {@code settings.LocalMusicScreen}.
 *
 * <p>Gotchas:
 * <ul>
 * <li>Methods named in patches must not be renamed. Local urns (a {@code LocalTrackUrn} made from a file path) must
 * never reach the {@code Likes} table or the server; the hooks above filter them out.</li>
 * <li>SoundCloud's classes are reached by reflection ({@code Urns}, {@code PlaylistOps}, {@code Rx}); 
 * {@code local.Urns} finds and makes SoundCloud's {@code Urn} objects by reflection, while {@code shared.Urns} only
 * parses urn strings such as {@code soundcloud:tracks:123}.</li>
 * <li>Pure rules without Android ({@code MergePlan}, {@code LikesPaging}, {@code HiddenLikes}, {@code LikedFiles},
 * {@code TrackMatcher}, {@code Debounced}) are covered by JUnit tests; keep them Android-free.</li>
 * </ul>
 */
package app.revanced.extension.soundcloud.local;
