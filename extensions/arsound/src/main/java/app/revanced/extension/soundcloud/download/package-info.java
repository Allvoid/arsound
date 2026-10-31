/**
 * Downloading tracks and playlists to the phone, and knowing which tracks are already downloaded.
 *
 * <p>User view: a "Download" row in the menu of a track, playlist or album, the round download button on playlists and
 * "Your likes", and SoundCloud's own download buttons save music files (default Music/Arsound, or any folder the user
 * picks). Downloads show on the library's Downloads screen, play without a network and carry title and artist tags.
 *
 * <p>Entry points (Kotlin: {@code download/*Patch.kt}, descriptors in {@code DownloadTrackPatch.kt}):
 * <ul>
 * <li>{@code DownloadTrackPatch}: {@code onTrackMenu}, {@code getDownloadIcon}, {@code getPlaylistDownloadIcon},
 * {@code setCurrentTrackItem}, {@code setOAuth}; {@code playableSource}/{@code parseTrackId} are also called by
 * {@code offline.DownloadedPlaybackPatch}. {@code DownloadPlaylistPatch.onPlaylistMenu} - the "Check downloads" row.</li>
 * <li>{@code DownloadButtons}: {@code setFeatureOperations}, {@code interceptNavigation}, {@code showPlanBadge};
 * {@code HeaderDownloadButton}: {@code onPlaylistHeaderBound}, {@code likesAction}; {@code DownloadsScreen}:
 * {@code addArsoundTracks}, {@code beforeState}, {@code stateOnScreen}, {@code setOfflinePropertiesProvider}.</li>
 * <li>Screen hooks (no patch, wired in {@code update.UpdateChecker}): {@code MusicAccess}, {@code DownloadIndex},
 * {@code DownloadProgress}, {@code ProgressPill}.</li>
 * </ul>
 *
 * <p>Main classes: {@code DownloadIndex} (where the file of a track is and whether the app can use it; the only place
 * to ask), {@code DownloadLocation}/{@code DownloadFolder}/{@code TreeFolder} (default, media store or a picked
 * folder), {@code ProgressiveDownloader} and {@code DownloadHttp}, {@code FileTags}/{@code MediaTags}/
 * {@code AudioFormat}/{@code FileNames}, {@code DownloadOffer} (what a subscription button wanted),
 * {@code DownloadAdoption}/{@code DuplicateFiles} (files after a reinstall, duplicate copies),
 * {@code UnplayedDownloads} (optional clean-up), {@code MusicAccess}, {@code TranscriptionAudio} (lets
 * {@code lyrics.transcribe} listen to a file; saves nothing).
 *
 * <p>Settings: {@code intercept_download_buttons} (default on), {@code download_folder}, {@code downloads_unplayed_*}.
 * Per-track records are in the preferences {@code revanced_soundcloud_downloads} ({@code track_uri_}, {@code track_dir_},
 * {@code track_file_}, ...); others: {@code arsound_downloaded_playlists}, {@code arsound_music_access},
 * {@code arsound_played_downloads}, {@code arsound_unavailable_tracks}, {@code revanced_soundcloud_download_parts}.
 * The per-track records and {@code arsound_played_downloads} go through {@code shared.BatchedPreferences}: changes reach
 * the disk together, at most every two seconds and when a screen leaves the front; never open those files directly.
 * The set of downloaded ids is read once and kept ({@code DownloadTrackPatch.getDownloadedTracks}, not to be changed).
 *
 * <p>Gotchas: <b>DRM work in progress, do not edit</b> {@code ClientProfiles}, {@code DownloadTrackPatch},
 * {@code DownloadPlaylistPatch}, {@code HlsDownloader}, {@code TrackSource}, {@code UnavailableTracks}. FairPlay and
 * Widevine streams are never exported, Go/Go+-only and preview tracks are skipped. Never build a file path by hand,
 * ask {@code DownloadIndex} (since Android 10 a path can exist and still be unreadable). Patched method names are fixed.
 */
package app.revanced.extension.soundcloud.download;
