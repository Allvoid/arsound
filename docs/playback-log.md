# Playback log

On by default (Settings → Arsound → Developer → Playback log, a change applies after a restart). It records every track start and switch, so a playback bug a user reports can be looked at afterwards.

- SoundCloud logs every playback step through Timber. The release app plants no tree, so the lines are lost.
  `PlaybackTimeline` plants one at app start and writes playback lines, all warnings and errors,
  and Arsound's own log lines to a file.
- One file per day: `Android/data/com.arsound.app/files/playback-log/playback-YYYY-MM-DD.txt`.
  Files older than two days are deleted. A day is capped at 20 MB.
- For every start the log has a summary line: `== START` or `== SLOW START` (1 second or more),
  time from the action (next, previous, a tapped track, auto next) to sound, split into
  SoundCloud's preparation (before ExoPlayer gets the track) and the stream download (after),
  the network at that moment, errors on the way and, for slow starts, the likely cause.
- A pause mid-track while waiting for data is logged as `STALL` with its length.
- Every slide of the player sheet (pulled up, pulled down, settling) writes one `jank:` line, to find where a stutter
  comes from. It gives the states the slide went from and to, whether "Smooth player collapse" was on, the screen's
  refresh rate, the number of frames, how many went over the time one frame has and how many were missed, about how many
  frames a second, the median, 90th percentile and maximum of a frame's total time, the average and maximum of each
  part of a frame (`anim`, `layout`, `draw`, `sync`, `issue`, `swap`, `gpu`, `late`) and the `worst` part. Nothing is
  measured at rest, and a slide cut short by the screen being rebuilt (a turn, a fold) is not logged.

A user saves the logs with Settings → Arsound → Developer → "Save the logs" (the first row): the playback log files
and the mod log (`arsound-log.txt`, if it is on) go into one file `Downloads/arsound-logs-YYYY-MM-DD-HHmm.txt`
with the Arsound, SoundCloud and Android versions on top. The app shows the path and offers to send the file.

Pull the logs with adb:

```
adb pull /sdcard/Android/data/com.arsound.app/files/playback-log
```
