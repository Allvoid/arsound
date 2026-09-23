# Playback log (personal build only)

This branch is not for releases. It records why a track starts slowly.

- SoundCloud logs every playback step through Timber. The release app plants no tree, so the lines are lost.
  `PlaybackTimeline` plants one at app start and writes playback lines, all warnings and errors,
  and Arsound's own log lines to a file.
- One file per day: `Android/data/com.soundcloud.android.revanced/files/playback-log/playback-YYYY-MM-DD.txt`.
  Files older than two days are deleted. A day is capped at 20 MB.
- For every start the log has a summary line: `== START` or `== SLOW START` (1 second or more),
  time from the action (next, previous, a tapped track, auto next) to sound, split into
  SoundCloud's preparation (before ExoPlayer gets the track) and the stream download (after),
  the network at that moment, errors on the way and, for slow starts, the likely cause.
- A pause mid-track while waiting for data is logged as `STALL` with its length.

Pull the logs:

```
adb pull /sdcard/Android/data/com.soundcloud.android.revanced/files/playback-log
```
