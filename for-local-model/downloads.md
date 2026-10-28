# Downloads (non-DRM flow)

Files in `EXT/download/`. Core files are DRM work in progress: read, do not edit (see rules.md).

Flow: UI hook -> `DownloadTrackPatch.requestDownload` / `downloadSilently` -> `resolveSource` -> `TrackSource`:
- READY (progressive URL) -> `enqueue` -> Android DownloadManager -> `Music/Arsound/<Artist - Title>`.
- REQUIRES_HLS_PROCESSING (.m3u8, open or AES-128) -> `HlsDownloader.download` -> remux to .m4a -> MediaStore `Music/Arsound`.
- Previews, subscription-only, unavailable -> toast + `UnavailableTracks` 12 h cache, no download.

Where to change what:
- File name: `FileNames.base`, `freeFileName`; tags: `MediaTags`.
- Playlist/likes bulk: `DownloadPlaylistPatch` (via `DownloadButtons`, `HeaderDownloadButton`).
- Progress UI: `DownloadProgress`, `ProgressPill`. Downloads screen: `DownloadsScreen`, `UnplayedDownloads`.
- State: SharedPreferences `revanced_soundcloud_downloads`; index of files: `DownloadIndex`.
- Network limits: every request first calls `RegionGuard.throwIfBlocked(host)`.
- Playback of saved files: `offline/InstantFilePlayback`, `DownloadedPlaybackPatch`.
- Smali hooks: `PAT/download/*.kt`.
