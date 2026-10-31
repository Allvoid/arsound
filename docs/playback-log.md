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
  comes from. It gives the states the slide went from and to, whether "Smooth player collapse" was on, the cadence of
  the frames and the screen's refresh rate apart (60 frames a second on a 120 Hz screen counts every other refresh as
  missed), the number of frames (a slide longer than 240 frames says how many of them were measured), how many went over
  the time one refresh of the screen has and how many were missed, about how many
  frames a second, the median, 90th percentile and maximum of a frame's total time, the average and maximum of each
  part of a frame (`anim`, `layout`, `draw`, `sync`, `issue`, `swap`, `gpu`, `late`) and the `worst` part. Nothing is
  measured at rest, and a slide cut short by the screen being rebuilt (a turn, a fold) is not logged.

A user saves the logs with Settings → Arsound → Developer → "Save the logs" (the first row): the playback log files
and the mod log (`arsound-log.txt`, if it is on) go into one file `Downloads/arsound-logs-YYYY-MM-DD-HHmm.txt`
with the Arsound, SoundCloud and Android versions on top. The app shows the path and offers to send the file.

The file has more than the logs of the app. After the header comes a snapshot of what decides whether the sign-in
works: the network (Wi-Fi, mobile, VPN, DNS, proxy), the clock and its automatic setting, the browsers that can show
the sign-in page, the WebView version and the Arsound settings that change the network. Then the crashes, the playback
log, the mod log and the end of the system log (`logcat`) of the app. Android shows an app only its own lines, so
nothing of other apps is read. The command has a 3 second limit and a 1 MB cap; if the phone refuses it, the file
has a line saying so. The sign-in codes and tokens (`sc://auth?code=...`, `access_token=...`, `Authorization`
headers) are replaced with `<redacted>` in everything that is copied.

Before the user is signed in the settings cannot be reached, so the first sign-in page (the whole sign-in
activity) has a "Save logs" pill in its top corner. It makes the same file and shows the same dialog with "Send".
There the file is made even when the app has written nothing yet, because the snapshot is what there is to send.
On the settings screen a file with no earlier records still gives "No logs yet".

The code: `debug/LogExport` makes the file from `LogSection` parts (`debug/sections/`), `debug/LogComposer` writes
them in order and turns a part that cannot be read into one line, `login/LoginLogsButton` is the pill (added from the
activity callbacks in `UpdateChecker`, no patch needed).

Pull the logs with adb:

```
adb pull /sdcard/Android/data/com.arsound.app/files/playback-log
```
