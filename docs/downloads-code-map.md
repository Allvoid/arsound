# Track downloads: code map

Line numbers as of 2026-10-27 (working tree after chat `Qwen-Cline-export-2`; DRM experiment is unverified). Re-grep method names if lines drift.

`EXT` = `extensions/arsound/src/main/java/app/revanced/extension/soundcloud`
`PAT` = `patches/src/main/kotlin/app/arsound/patches/soundcloud`

## Flow

```
UI hook -> requestDownload / downloadSilently -> resolveSource -> TrackSource
  READY (progressive URL)          -> enqueue -> Android DownloadManager -> Music/Arsound/<name>
  REQUIRES_HLS_PROCESSING (.m3u8)  -> hlsAssembly pool -> HlsDownloader.download -> MediaStore Music/Arsound
  DRM_PROTECTED with URL (current experimental route) -> HLS assembler, not a valid DRM decryptor
  other status                     -> toast / UnavailableTracks cache, no download
```

## Server requests (all `HttpURLConnection`, timeouts 15s connect / 30s read, `RegionGuard.throwIfBlocked(host)` first)

| # | Request | Auth | Where |
|---|---|---|---|
| 1 | `GET api-v2.soundcloud.com/tracks/{id}` | app OAuth header | `DownloadTrackPatch.resolveSource` :551 |
| 2 | `GET .../tracks/{id}/download` -> JSON `redirectUri` (author file) | OAuth | same :562; skipped if track `downloadable:false` |
| 3 | `GET <transcoding.url>` -> JSON `url` (CDN file or m3u8) | OAuth | `resolveStreamUrl` :680 |
| 4 | `GET <m3u8>` (master/media, recursive) | none | `HlsDownloader.isDrmProtected` :277, `parsePlaylist` :344 |
| 5 | `GET <EXT-X-KEY uri>` 16-byte AES key, cached per assembly (map made in `writeDecrypted`) | none | `HlsDownloader.keyBytes` :536 |
| 6 | `GET <segment>` x N, 4 parallel, 8 ahead; stops at the next segment when the track is deleted | none | `HlsDownloader.writeDecrypted` :469 / `decrypt` :492 |
| 7 | `GET <progressive url>` | none | Android DownloadManager, `enqueue` :759 |
| 8 | Fallback: `GET soundcloud.com/` + its `<script src>` bundles, regex `client_id` (cached 24h) | UA only | `ClientProfiles.readClientId` :202, `findClientId` :225 |
| 9 | Fallback: `GET api-v2/tracks/{id}?client_id=` (2 desktop UAs) | client_id | `ClientProfiles.search` :69 |
| 10 | Fallback: `GET <transcoding.url>?client_id=&track_authorization=` | client_id + track token | `ClientProfiles.resolveStream` :141 |
| 11 | Playlist: `GET api-v2/playlists/{id}`, `/me`, `/users/{u}/track_likes?limit=200&linked_partitioning=1`, `/tracks?ids=` (50 per chunk) | OAuth | `DownloadPlaylistPatch.trackIdsOf` :285, `likedTrackIds` :249, `check` :373 |

## Files

### `EXT/download/DownloadTrackPatch.java` (core)
- `setOAuth` :83 — hook stores SC OAuth helper; `getAuthorization` :899 finds its no-arg String method by reflection, value `OAuth ...`.
- `apiGet` :326 — authorized GET, returns `{code, body|null}`; global 120ms gap (`waitForApiSlot` :299), 429 retry x3 with Retry-After (max 15s).
- `openApiConnection` :730 — sets `Authorization`; `apiSend` :704, `apiDelete` :720 reuse it (non-download callers).
- `isTransient` :697 — 429/401/5xx => throw, not "unavailable".
- `resolveSource(id, known)` :544 — order: author file (2) -> `sourceOfTrack` -> `ClientProfiles.search`.
- `sourceOfTrack` :602 — `restrictionOf` :755 (SNIP/SUB/GO_PLUS/BLOCK => refuse), then `media.transcodings` sorted by `rank` (progressive < hls < other; `lq` last), only `audio/*`; DRM playlist marks protocol locked while trying alternatives. If only DRM streams were seen, current experimental code returns the last URL as ordinary HLS.
- Entry points: `onTrackMenu` :90 -> `addDownloadRow` :188 -> `requestDownload` :262; `downloadFromSoundCloudButton` :249; `downloadSilently` :362-395 (playlists).
- `start` :398 — READY -> `enqueue`; HLS -> `hlsAssembly` (2 threads :115), `awaitNetwork` (up to 10 min), `freshHlsSource` :513 (re-resolve if link older than 60s). `deleteDownload` adds an assembling track to `cancelledAssemblies`; `start` hands `cancelledAssemblies.contains(trackId)` to `HlsDownloader.download`, so the assembly stops at the next segment. `NoSpaceException` => `showNoSpace` :462.
- Line numbers below `start` in this file predate the cancel/no-space edit (+21 lines) and some were off before it; re-grep.
- `enqueue` — DownloadManager, hidden notification, `Music/Arsound/<fileBase + extension of the server file>`, else `<lastPathSegment or soundcloud-{id}.mp3>`. `freeFileName` (package-private, also used by `HlsDownloader`) adds `-{id}` when a file of that name exists or the name is recorded for another track.
- State (SharedPreferences `revanced_soundcloud_downloads`): `rememberDownload` :806 (`downloaded_tracks`, `track_file_`, `track_job_`, `track_replaces_`), `settleDownloads`/`settle` :831/:841, `getDownloadState` :928, `deleteDownload` :964, `getDownloadedFile` :1063, `knownFile` :1047.

### `EXT/download/TrackSource.java`
Result type. `Status` enum :15; factories `progressive`, `hls`, `unavailable`; `isFresh`. `withTrack(JSONObject)` returns a copy with `title`, `artist` (`publisher_metadata.artist`, else `user.username`) and `fileBase` ("Artist - Title" from `FileNames.base`, null if the track has no title). Called after the source is made in `resolveSource` (author file, `sourceOfTrack`) and in `ClientProfiles.search`; `sourceOfTrack` itself is untouched. A source made without the track's data (`downloadSilently` with a bare URL) keeps the old names.

### `EXT/download/FileNames.java`
`base(title, artist, artistIsUploader)` — "Artist - Title"; the artist is not put in front when the title already starts with it, or when the artist is only the uploader and the title has " - ". Replaces `\ / : * ? " < > |` and control characters with `_`, collapses spaces, trims edge dots, cuts to 160 bytes of UTF-8.

### `EXT/download/HlsDownloader.java`
- File name and tags: `download` names the file `source.fileBase + ".m4a"` (or the mime extension), `soundcloud-{id}` without track data, and takes a free name from `DownloadTrackPatch.freeFileName`. `publish` writes `TITLE`/`ARTIST` to the media store row with `MediaTags.tag`, after IS_PENDING is cleared (the scan that clearing starts would overwrite earlier values). The file has no embedded tags, and nothing is tagged before Android 10 for HLS files (no row).
- `EXT/download/MediaTags.java` — `tag(context, row, title, artist)` updates the row (best effort, result logged as `Tagged <uri>: N row` or `Could not tag`). `tagSavedFile(context, file, ...)` is for files the download manager saved: finds the row by name and `Music/Arsound`, else scans the file with `MediaScannerConnection` and tags the row the scan reports. The row may belong to another app (the download manager): then the update is refused and only the log says so.
- Progressive files: `DownloadTrackPatch.enqueue` keeps title and artist in `revanced_soundcloud_downloads` (`track_title_`, `track_artist_`) through `rememberTags`; `settle` reads and removes them when the job ends, and on success calls `MediaTags.tagSavedFile` on a background thread. `deleteDownload` removes them too.
- `download` :120 — takes the `TrackSource` (url, mime, title, artist, name) and a `BooleanSupplier cancelled` (`DownloadTrackPatch.start` passes `cancelledAssemblies.contains`; true => `CancelledException` inside, result `null`, no "failed" toast). Parse, fetch+decrypt into cache `.ts`, then `remux` :554 (MediaExtractor/Muxer -> `.m4a`) or `dropSegmentIndexes` :202 or raw; `publish` :641 (MediaStore `Music/Arsound`, IS_PENDING).
- Free space: `requireFreeSpace` :247. Needs `MIN_FREE_BYTES` (50 MB) before start and `2 x .ts size + 50 MB` before packing; a failed write with < 25 MB left also counts. All throw `NoSpaceException`, which leaves `download`; `DownloadTrackPatch.start` answers with `showNoSpace` (toast at most once a minute, also for playlist runs) instead of the "Could not assemble" toast.
- `isDrmProtected` :277 — non-`identity` KEYFORMAT or SAMPLE-AES => DRM; follows the highest-bandwidth master variant. `parsePlaylist` :344 selects a variant and parses `#EXT-X-KEY` / `#EXT-X-MAP`. `#EXT-X-MEDIA-SEQUENCE` is a `long` (segment `sequence` too, IV uses 8 bytes); an unreadable number throws `IOException` instead of a guessed IV.
- `requireSupportedKey` :396 currently logs and accepts non-identity / SAMPLE-AES keys due to the chat experiment. This is **not DRM support**: the downloader has no Widevine/FairPlay license integration and the segment decryptor is only AES-128/AES-CTR. Treat that route as unverified; it can fail or emit invalid audio.
- `parsePlaylist` :344 (STREAM-INF -> best variant, MEDIA-SEQUENCE, KEY, MAP), `parseKey` :419.
- `decrypt` :492 — CBC (strip padding) or CTR; IV from key or `sequenceIv`; init segment (MAP) never decrypted. This does not implement SAMPLE-AES or DRM license decryption.
- Network: `open` :786, `readBytes` :760, `readText` :773, `withRetry` :732 (3 retries, 408/429/5xx/IO only, Retry-After).

### `EXT/download/ClientProfiles.java`
Web-player fallback when app gets no stream (see requests 8-10). `forget` :254 drops client_id on 401. Also `searchTracks` :122 (search UI, not downloads).

### `EXT/download/DownloadPlaylistPatch.java`
Playlist / likes bulk download. `onPlaylistMenu` :111, `checkPlaylist` :326, `checkLikes` :332, `check` :345 (chunk metadata, parallel `resolveSource` with `UnavailableTracks` cache ~:430), `downloadAll` :538 -> `downloadSilently`.

### Support
- `UnavailableTracks.java` — 12h cache of non-downloadable status per track. Expired entries are dropped by `get` for the one asked, and by `sweepOnce` (first `get`/`put` of the process) for all.
- `DownloadProgress.java` — in-progress state, assembly slots (`tryBeginAssembly`, `onAssemblyFinished`), calls `settleDownloads` :181.
- `ProgressPill.java` — bottom progress counter for playlist runs.
- `DownloadButtons.java` — SC's own Download button -> `downloadFromSoundCloudButton` :98, `checkPlaylist` :114, `checkLikes` :122.
- `HeaderDownloadButton.java` — playlist/likes header button -> `checkPlaylist` :60 / `checkLikes` :29.
- `DownloadsScreen.java`, `UnplayedDownloads.java`, `MusicAccess.java` (storage permission) — UI/read side.
- `EXT/network/RegionGuard.java` :119 `throwIfBlocked` — throws `BlockedException` (IOException), rethrown, never treated as "unavailable".
- Playback of saved files (read only): `EXT/offline/DownloadedPlaybackPatch.java`, `InstantFilePlayback.java` -> `getDownloadedFile`.

### Smali hooks (`PAT/download/`)
- Downloads screen has two designs: old `DownloadsDataSource$loadTracksAndPlaylists$2` (`downloadsFilterMethod`) and new Compose `DownloadsV2DataSource$loadContent$offlineCollectablesFlow$1` (`downloadsV2FilterMethod`, the one the emulator account gets). Both call `DownloadsScreen` (`addArsoundTracks` / `withArsoundTracks`, `beforeState` + `stateOnScreen`). The Library row: `upsell/PaywallScreensPatch.kt` forces `isOfflineContentEnabled` in the old `LibraryPresenter$attachView$14` and in the new `LibraryViewModel$observeLibrarySections$1...$map$1$2.emit` (otherwise the row is the Upsell state and opens the hidden paywall).
- `DownloadTrackPatch.kt` — `setOAuth` (:103, OAuth class), `onTrackMenu` (:119), `onPlaylistMenu` (:144), icons (:158/:169/:179), Downloads screen (:84-97); adds `DOWNLOAD_WITHOUT_NOTIFICATION` to manifest (:27).
- `Fingerprints.kt` — target classes (OAuth, TrackBottomSheetFragment, PlaylistBottomSheetDialogFragment, MetaLabelsKt).
- `DownloadButtonsPatch.kt`, `HeaderDownloadButtonPatch.kt` — button hooks.

## Where to change what
- Auth / headers / rate limit: `DownloadTrackPatch.openApiConnection`, `apiGet`.
- Which stream is picked: `rank`, `orderedTranscodings`, `sourceOfTrack`.
- Restrictions and DRM detection: `restrictionOf`, `HlsDownloader.isDrmProtected`; DRM is not verified downloadable. The experimental HLS fallback is in `sourceOfTrack` and `resolveSource`.
- File name / folder: `enqueue`, `freeFileName`, `HlsDownloader.download` + `publish`.
- HLS speed / retries / free-space floor: `PARALLEL_SEGMENTS` :53, `SEGMENTS_AHEAD` :55, `MAX_RETRIES` :62, `MIN_FREE_BYTES` :71 in `HlsDownloader`.
- Related analysis docs (repo root): `DRM-tracks.md`, `SC-MOD-2025.12.29-DOWNLOADS.en.md`.
