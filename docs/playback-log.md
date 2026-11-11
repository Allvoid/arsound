# Playback log

On by default (Settings → Arsound → Developer → Playback log, a change applies after a restart). It records every track start and switch, so a playback bug a user reports can be looked at afterwards.

- SoundCloud logs every playback step through Timber. The release app plants no tree, so the lines are lost.
  `PlaybackTimeline` plants one at app start and writes playback lines, all warnings and errors,
  and Arsound's own log lines to a file.
- One file per day: `Android/data/com.arsound.app/files/playback-log/playback-YYYY-MM-DD.txt`.
  Files older than two days are deleted. A day is capped at 20 MB; the line that reaches the cap is followed by a
  note saying that nothing more is written until the next day.
- The file of the day stays open and is written to the disk once no more lines wait: a burst of lines is one
  write, a single line is on the disk at once. At most 4000 lines wait; if something logs faster, the rest is left
  out and the next written line says how many.
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

## Widget log

For a tap on the player widget that does nothing (HyperOS, bug #26) the app keeps a log of its own, `files/arsound-widget-log.txt` (and `.1`, 96 KB each, in the app's private storage). It is written whatever the log settings are and is not deleted after two days, so a file sent a week later still has the widget's lines. The same lines also go to the playback log. `widget/WidgetLog` writes it, `widget/WidgetDiagnostics` makes the lines.

- `process start: …, started by <reason>, <cold/warm/hot> start, intent <action>` at every process start. The reason comes from Android 15's start history. `BROADCAST` with the action `com.soundcloud.android.HomescreenWidgetBroadcastReceiver.ACTION` is a tap that woke a dead app.
- `tap arrived: action …, sent from <launcher package> uid …, process started N ms ago, process importance …, the widget was showing …`, written at the first line of SoundCloud's receiver. Then `tap handled:` right after the receiver ends and `tap 1500 ms later:`, each with what the player says (`playing true/false`, or "not filled by the app's injection").
- `buttons wired …` at the first build of the widget in a process and at each change of the play state.
- `the system sent …` for what the system tells the widget (added, resized, removed; the first refresh in a process).
- `phone …` (model, firmware, battery optimisation, standby bucket, Xiaomi autostart and "screens from the background") and `on the home screen …` (widgets placed with their sizes, whether the components are enabled, whether the system still holds the intents of the previous, pause and next buttons) at each start of the main process.

- `delivery self-test (process start | saving the logs): plain broadcast delivered in N ms, through a PendingIntent delivered in N ms, process importance …`. The app sends its own receiver (`player/PlayModeReceiver`) a harmless broadcast two ways, plain and through a PendingIntent (the way the launcher sends a tap), and waits up to 8 s for both (`WidgetDiagnostics.runSelfTest`). It runs at each start of the main process and when the logs are saved (shown in "widget state" as `tap delivery`). It does not touch the player. Both delivered means the phone lets a broadcast reach the app; `NOT delivered` means the system blocks it (on Xiaomi: autostart, battery saver). At a start the main thread is busy, so a second of delay there is normal.

Way of the buttons (1.2.1-test.3): the `buttons wired` line ends with `delivery: broadcast | activity (why)`; the why is `auto: Xiaomi firmware`, `auto: not Xiaomi`, `auto: Xiaomi, but a broadcast tap arrived before` or `chosen: …` (Settings → Arsound → Playback → "Widget buttons"). With `activity`, a tap shows `relay: passed on … in N ms` (the invisible screen, `WidgetRelayActivity`) and then `tap arrived via activity:` (the same receiver, the broadcast marked as relayed). A tap by the launcher's own broadcast writes the usual `tap arrived:` and, once, `a tap arrived by SoundCloud's own broadcast: automatic keeps that way on this phone` (Auto then stays on SoundCloud's way even on Xiaomi). A tap with neither `relay:` nor `tap arrived` after it never reached the app even through a screen.

How to read the self-test with the taps: self-test delivered and the user's taps never arrive (no `tap arrived`) means the launcher or the system stopped the tap itself, not the app's receiver; self-test not delivered means the system blocks broadcasts to the app, and the taps die for the same reason.

How to read it: a tap the user made with no `tap arrived` after it was stopped before the app (the launcher or the system); then look at the next `process start` and the `recent starts` of the "widget state" part to see if the system started the app for it. `tap arrived` with a new process and "the tap did nothing" means the receiver ran with no player state (the process was started cold, nothing was queued); `tap arrived` with a live process and an unchanged `playing` means the player ignored it.

The saved file has two more parts for this: "widget state" after the sign-in environment (the phone, the home screen app and its version, the widgets, the last ten starts of the app's process with their reasons) and "widget log" after the crashes (the file above).

A user saves the logs with Settings → Arsound → Developer → "Save the logs" (the first row): the playback log files
and the mod log (`arsound-log.txt`, if it is on) go into one file `Downloads/arsound-logs-YYYY-MM-DD-HHmm.txt`
with the Arsound, SoundCloud and Android versions on top. The app shows the path and offers to send the file.

The file has more than the logs of the app. After the header comes a snapshot of what decides whether the sign-in
works: the network (Wi-Fi, mobile, VPN, DNS, proxy), the clock and its automatic setting, the browsers that can show
the sign-in page, the WebView version and the Arsound settings that change the network. Then the crashes, the playback
log, the mod log and the end of the system log (`logcat`) of the app. Android shows an app only its own lines, so
nothing of other apps is read. The command has a 3 second limit and a 1 MB cap; if the phone refuses it, the file
has a line saying so. The sign-in codes and tokens (`sc://auth?code=...`, `access_token=...`, `Authorization`
headers), cookies (`Cookie:` and `Set-Cookie:` lines), sessions of other services, YouTube's proof of origin
(`pot`, `po_token`), the signature of an address of the audio servers (`Policy`, `Signature`, `Key-Pair-Id`) and
the authorization of a track (`track_authorization`) are replaced with `<redacted>` in everything that is copied.

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
