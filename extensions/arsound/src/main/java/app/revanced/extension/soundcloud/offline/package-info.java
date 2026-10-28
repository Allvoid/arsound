/**
 * Offline behaviour: stored data first, downloaded files played from disk, retry on a flaky connection.
 *
 * <p>User view: playlists, albums, tracks, profiles and the Following list open at once from what is stored on the
 * phone and refresh quietly in the background; a track downloaded by Arsound plays from its file (no network, no
 * mobile data); a timeout while streaming is retried instead of showing "Track cannot be streamed".
 *
 * <p>Entry points (Kotlin side: {@code offline/*Patch.kt}):
 * <ul>
 * <li>{@code OfflineFirstPatch.isEnabled/syncInBackground/detachPlaylistSync/localTracksFirst/localUsersFirst} - the
 * three places where SoundCloud waited for the server (playlists of others, the 24 hour full sync, one missing
 * track) plus the track and user repositories.</li>
 * <li>{@code DownloadedPlaybackPatch.playableFilePath(Object track)} - the file (or media store row) to play, or null
 * to stream. It asks {@code download.DownloadTrackPatch.playableSource}.</li>
 * <li>{@code InstantFilePlayback.playbackItem/metadata/isStartWithoutMetadata} - build the playback item of a
 * downloaded track without waiting for the track repository, and cap the notification metadata wait at 2 s.</li>
 * <li>{@code PlaybackRetryPatch.onPlaybackError(Object player, boolean connected)} - true when the error was handled by
 * a retry.</li>
 * <li>{@code PlaylistPreloader.setMyPlaylistOperations(Object)} - captures SoundCloud's playlist operations; the
 * preloader then saves titles and artwork links of every library playlist ahead of time (no audio).</li>
 * <li>{@code FollowingsCache.begin/wrap/beginStatuses/wrapStatuses} - remembers loaded pages of the Following screen
 * and answers from them when the network fails.</li>
 * </ul>
 *
 * <p>Settings: {@code offline_first} (default on), {@code playback_retry} (default on), {@code playlist_preload}
 * (default on), all in {@code settings.Settings}. Own preference files: {@code arsound_followings},
 * {@code arsound_playlist_preload}.
 *
 * <p>Gotchas: all these methods take {@code Object} and use reflection because SoundCloud's classes are obfuscated
 * and not on the compile path; never rename the patched ones. Playlist covers saved by the preloader are kept as files
 * by {@code local.LocalCovers}.
 */
package app.revanced.extension.soundcloud.offline;
