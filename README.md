# Arsound

English · **[Русский](README.ru.md)**

A mod for the Android SoundCloud app with track downloads, your own music from your phone, and
playlists that open instantly — even without internet.

Ready-to-install APKs of every version are posted in the Telegram channel [t.me/arsound_git](https://t.me/arsound_git).
This GitHub repository holds the source code and the patch set for ReVanced Manager.

Arsound is a set of patches based on [ReVanced](https://revanced.app). It is an unofficial project, not affiliated
with ReVanced or SoundCloud.

> Tested on SoundCloud **2026.10.14-release**, Android 16, arm64.
> With another SoundCloud version some features may not work.

## Features

Everything is configured inside SoundCloud: **Settings → Arsound**, split into sections.
(Menu and button names below are given as they appear in the English interface; the app itself can be switched
between Russian and English in Settings → Arsound → Appearance.)

The magnifier at the right of the "Arsound" title searches all settings: the field grows out of the button, results appear
as you type (by a setting's name, its section or a word from its description), and a tap opens the right screen, scrolls
to the setting and lights it up. The search follows the theme's colours.

**No clutter**
- No ads: no audio ads between tracks, no banners in the player, library and feed. Since 24.10.2026 SoundCloud also puts an ad at the start of a new play queue; Arsound does not let it into the queue either (before, it stopped playback on a full screen ad page whose sound never loaded).
- No pushy Go, Go+ and Pro subscription offers: no purchase screen, no banners on the home screen and in the library, no "Get Pro" and "Upgrade" buttons in the header. The Upgrade tab, which in the original always ended in "Oops" (a subscription is sold only through Google Play, and Arsound is not installed from there), explains this instead and has a "Hide the tab" button; it can also be hidden in Settings → Arsound → Ads and subscription.
- No "Transfer your gems" banner in the library: it has its own close button, but comes back by itself after a few days.
- No "Share screenshot" sheet after every screenshot (Android 14+). It can be turned back on in Settings → Arsound → Ads and subscription; then it opens once per screenshot, not twice as in the original on the player screen.
- Telemetry is turned off.
- Duplicates in recommendations (re-uploads of the same track) can be hidden: on the home screen, in autoplay and in the mixes SoundCloud builds for you (Your Mix, Daily Drops, Weekly Wave). Optionally, versions at a different speed ("0.9 speed", "1.2x", slowed) and remixes count as the same track.
- "For you" playlist: once a day Arsound takes your likes, playlists, imported and most-played tracks, asks Last.fm what people listen to alongside them, and puts up to 40 tracks that are new to you from SoundCloud into a playlist — at most two per artist, without tracks you already know or marked "Not interested", and without re-uploads of the same song. The playlist refreshes every day at 00:00 without opening the app; if there is no network at that moment or the IP is Russian, the refresh runs at the first opportunity. You can also refresh it by hand: Settings → Arsound → Recommendations. Only track and artist names are sent to Last.fm. Last.fm and SoundCloud do not work from a Russian IP, so the refresh needs a VPN there.
- "Not for me: don't recommend" in a track's "⋮" menu: the track disappears from the home screen, autoplay and SoundCloud's mixes. The list is stored on the phone, and the track can be restored in Settings → Arsound → Recommendations. Likes, playlists and search are unchanged.

**Downloads**
- "Download file" in a track's menu — saves to the `Music/Arsound` folder, if the whole track is available to you. "Download file again" keeps the old file until the new one is complete, so a download that fails on a bad network does not cost the track its file; a download that failed is no longer counted as downloaded.
- "Check track downloads" in a playlist's menu: how many tracks are already downloaded and how many more can be. The check requests the file link right away, so tracks blocked in your region land in "unavailable", not "can be downloaded". Already downloaded tracks are not downloaded again. Several tracks are checked at once; a track found unavailable is remembered for 12 hours, so re-checking the same playlist is instant. A track the server gave no answer about (rate limit, network or server error) is listed as "could not be checked" and is not remembered: check again later.
- SoundCloud's own download buttons download with Arsound: "Download" in the menu of a track, a playlist and an album, the button on the track page. In the original they need Go or Go+ and otherwise open the subscription offer (hidden in Arsound, so a tap did nothing). The track menu item loses its "Go" badge; a track downloaded already is not downloaded again by it ("Download file again" does that). With a subscription SoundCloud downloads by itself, so its own download keeps working. Settings → Arsound → Ads and subscription → "SoundCloud download buttons use Arsound" (on by default) gives the buttons back to SoundCloud entirely.
- The round download button at the top left of a playlist, an album and "Your likes" does the same check with Arsound (in the original it downloads only with Go+ and otherwise opens the subscription offer). For the likes it checks every liked track. Playlists made by SoundCloud itself ("Related tracks", mixes) have no such button: there is no playlist to check, and in the original it only opened the subscription offer.
- While a playlist is being checked or downloaded, a small badge with a count hangs at the bottom of the screen — "14 of 48". It sits above the mini-player, or above the bottom tabs when there is no player, and slides smoothly when the player appears or goes away. It is hidden while the player is expanded to full screen.
- Downloaded tracks play straight from the file — without internet, without traffic and without waiting for SoundCloud, even if there is a connection that does not respond. The "next" button on headphones and in the notification also switches to a downloaded track instantly.
- The library opens offline: playlists (including ones made only of tracks on the phone), albums, likes and followings are shown from the phone's memory right away, the server's answer follows. Covers of library playlists and albums are stored on the phone as files (updated if the cover changes on the server), so even offline the library, "Recently played" and the playlist header have pictures.
- The Downloads section shows tracks and playlists downloaded by Arsound — without a SoundCloud subscription. A playlist counts as downloaded when a download was started from its check, or the check found everything available already on the phone. "Delete downloaded tracks" removes the mark, and an open Downloads screen refreshes immediately.
- "Storage" (Settings → Arsound → Storage): how many tracks are downloaded and how much space they take, deleting all downloads with one button after a confirmation, the size of the stream cache. Files downloaded before a reinstall are removed by Android only after its own confirmation, which the app shows.
- There too: deleting the files of unplayed tracks (off by default). If a downloaded track was not played for longer than the chosen period (7, 30, 90 days, a year or any number from 1 to 365), its file is deleted when the app starts. The period counts from the last play, or from the download for tracks never played. The track stays in SoundCloud: it can be streamed and downloaded again. Tracks imported from the phone are deleted by the same period only with "Delete imported tracks too" on (off by default). The copy of a downloaded track that SoundCloud deleted is never deleted: nothing could bring it back.
- In library cards, after the track count there is a download icon and the number of tracks that play from the phone: downloaded by Arsound and imported. In the playlist header the same number follows an arrow: "38 tracks · ↓ 28". The track count in cards also includes tracks added only on this phone (a playlist made of such tracks used to show "0 Tracks"). The lists of all library playlists are read from the phone's memory after launch, you don't need to open each one.
- If Arsound was reinstalled, the app asks for access to music: without it Android does not let it read tracks downloaded before the reinstall.
- Everything SoundCloud serves without protection can be downloaded: if the app is refused a stream, the same track is requested the way the web player does it, and all stream variants are tried in turn.
- Subscriber-only tracks and previews are not downloaded. Tracks that only have DRM-protected streams (FairPlay, Widevine) can be played but not saved — the menu and the playlist check say so: "protected by DRM".
- Tracks served only as a protected HLS stream are assembled by the app itself: segments are downloaded, decrypted and packed into a single `.m4a` file — a regular MP4 with the whole length in its header (before 0.9.3 the file kept the index of its first segment, so some players showed ten seconds and stopped; such tracks need to be downloaded again; if the old file is left from an earlier install and cannot be replaced, the new one is saved next to it as `name (1).m4a`, and the track plays the new one). It takes a few seconds after the download, and the track shows a spinner the whole time; a whole playlist is assembled two tracks at a time, so a long one does not run the app out of memory; a segment that fails on a bad network is requested again (up to three times), and while there is no network the queue waits for it instead of losing tracks; in the playlist check such tracks are marked "background processing". How it works — [docs/protected-tracks.md](docs/protected-tracks.md) (in Russian).
- While a track is downloading, its download icon spins; for a playlist — while the tracks started from its check are downloading.
- **Arsound search** — for tracks that can't be downloaded from SoundCloud (not in the catalog, subscriber-only, preview, DRM). On the Search tab, under the search bar, there is a "SoundCloud / Arsound" switch; a small "?" on the Arsound half explains what it is. Arsound searches YouTube Music: tap a track to listen and check it is the right one; the button on the right downloads it into the "Imported" playlist, from where it can be added to any playlist. Besides tracks, artists and albums are found: an artist opens as a list of their albums, an album as a list of tracks with a "Download all" button. The album cover is saved with the track: one copy per album, shared by all its tracks. YouTube serves age-restricted tracks only after sign-in: optionally sign in to YouTube Music in Settings → Arsound → Account (the password is entered on Google's page, the session is stored only on the phone); if you try to download such a track without signing in, the app offers to do it. When YouTube takes your network for a bot ("Sign in to confirm that you're not a bot", common with VPNs), the track is asked for once more the way the YouTube Music website does it, with Google's BotGuard tokens; if that is refused too, the app offers to sign in (with an account it suggests another network or VPN server). How it works — [docs/downloading-tracks.md](docs/downloading-tracks.md) (in Russian).
- "Delete the downloaded file" in a track's menu and "Delete downloaded tracks" in a playlist's menu (with confirmation). Music imported from the phone is not touched by this: it is deleted by hand, one track at a time.

**Your own music**
- Import audio files from the phone: from any playlist's menu, from "Import my music" or from the Arsound settings.
- Watch folder (Settings → Arsound → Your music → "Watch a folder"): new audio files in the chosen folder on the phone are imported by themselves when you open SoundCloud; when you pick the folder, what is already in it is imported too. Files imported earlier by hand (same name and size) are not copied a second time. Deleting a file from the folder does not touch the imported track.
- The "Imported" playlist with all your files. There is always exactly one: empty copies that old versions created after a reinstall are deleted from SoundCloud and disappear from the profile.
- Imported tracks in a playlist have a note icon next to "⋮" — so you can tell them from SoundCloud tracks.
- "Set cover" in the "⋮" menu of an imported track: a picture from the gallery is cropped to a square and shown in lists and in the player, instead of the picture from the file. "Remove own cover" brings the old one back.
- A playlist or album without SoundCloud tracks (empty or made only of imported ones) has "Set cover" in its menu: the picture shows in the header and in the library, on this phone only. The "Recently played" card picks it up on the next launch. Without a custom cover such a playlist takes the cover of the first track added on the phone, the way SoundCloud does for server tracks.
- "Check track downloads" also counts tracks added to a playlist on the phone (including in bulk): a playlist made only of such tracks used to look empty — "Nothing to download".
- Old covers with black bars (a 16:9 video frame) are cropped to a square by themselves, once.
- If SoundCloud removes a track, it no longer vanishes from the playlist silently. Arsound remembers the contents of playlists and checks the missing track: a downloaded one becomes imported (the file is copied to "Imported") and returns to its former place; one that was not downloaded stays in place, greyed out — it can't be played, and tapping it explains why. A track that was simply removed from the playlist is forgotten as usual. Once a week downloaded tracks are additionally checked in full — this finds the ones removed before this feature existed (they appear in "Imported").
- Tracks and files can be added to any playlist, even someone else's — on your phone only, nothing is sent to the server.
- Your own order of playlists in the library and of tracks inside a playlist: hold a row and drag. Playback follows the same order.

**Poor internet**
- Playlists and albums open instantly: their contents are saved ahead of time as text, without music; the covers of the playlists themselves as small files.
- If a track breaks off because of the connection, the player retries the load by itself.
- The next track from the network starts right away, without waiting for the notification cover: on a slow connection the old track no longer keeps playing while the player already shows the new one at 0:00.
- Each cover is downloaded once: the player, the notification and the lists that ask for it at the same time or within a minute get the same copy.
- Stream cache: SoundCloud keeps parts of tracks played over the network (120–500 MB). In Settings → Arsound → Offline and playback you can choose a size from 250 MB to 20 GB and a retention period (1, 7, 30 or 90 days without listening), and clear the cache. Files are deleted at launch, before the player opens the cache; a cleared cache is removed in the background, so it does not hold up the start. The chosen size is limited by the free space: at least 1 GB stays free for other apps and downloads.
- Your own DNS (for example xbox-dns.ru), if your provider interferes with SoundCloud.
- The app can be forbidden to go online from an IP of chosen countries — both to SoundCloud and to Arsound search. Russia, Ukraine, Belarus and Kazakhstan have a tick each (Russia is ticked by default), any other country can be added by its two-letter code. The country is checked again when the network or VPN changes and every 30 seconds while requests are running (both checks can be turned off and run by hand); if a check fails (unstable network, VPN reconnecting), requests wait for the next one instead of trusting the old answer. When there is no network or SoundCloud is disabled, a small crossed-out globe icon in the theme's colours appears at the bottom right above the player: a tap checks the network again, a long press explains what is going on.
- SoundCloud's device check no longer opens by itself on top of the app — it asks first.

**Player**
- A play mode button in the media notification and on the lock screen, after the like: in order → repeat the queue → repeat the track → shuffle, each tap moves to the next mode, and the icon shows the current one. It changes the same play queue as the buttons in the player's "Next up" screen. Going from shuffle back to in order also works after autoplay or "Play next" added tracks (before, the mode stayed on shuffle).
- "Play along with other apps" (Settings → Arsound → Offline and playback, off by default): music does not pause or get quieter when another app plays sound — a video, a voice message, a game or another player; both play. Calls still pause it, and it resumes after the call. Music also keeps playing while you record a voice message or a video.
- On Android 12L and older a like from the notification now changes the heart there at once (before, it changed only after a pause, so a second tap could not take the like back).
- The notification and the lock screen show the cover of the playing track (on Android 13+ they showed a SoundCloud placeholder). With the new player the notification is in the theme colours; "Notification player in the track's colours" brings back the cover and its colours.
- The control centre and lock screen player of vivo phones (OriginOS) shows only the apps on its own list, and Arsound is not on it, so the player says "Not playing". On a vivo phone Arsound explains this on start (never over the greeting or another dialog; it has a "Do not show again" box) and gives a command for a computer with adb (it also runs on the phone itself over wireless debugging, for example in aShell with Shizuku); after it Arsound is on the list, and if a vivo update resets the list, Arsound adds itself back on start. The hint and the state: Settings → Arsound → Offline and playback → "The vivo control centre player". Without a computer, there is a separate build for Chinese firmware (the `Arsound-<version>-chinese-firmware.apk` file in the Telegram channel): it uses a package name the vivo player already knows, so it shows in the control centre right away. It is a separate app next to the usual Arsound: you sign in to it again, and it has its own settings and downloads.
- New player (beta, Settings → Arsound → Appearance → "New player"): the full-screen player in the style of the chosen theme — the artwork fades into the theme colour at the top and under the waveform, the title and artist without black boxes and in the theme font, the waveform and time in the accent, the round buttons in dark glass. Works with every Arsound theme; the same switch turns it off.
- Sleep timer: in the full-screen player, "⋮" → "Sleep timer". Two modes, switched like the search source: "In time" (wheels for hours and minutes, buttons 15 min, 30 min, 1 h, 2 h) or "At a time" (a clock time; a time that has passed today means tomorrow). The window shows when the music stops; when the time comes, playback pauses. While the timer runs, the menu row shows the stop time, and the window can change or turn off the timer.
- Preamp (Settings → Arsound → Equalizer, on top): the overall volume from −12 to +12 dB in half-decibel steps, without changing the equalizer and with the equalizer turned off too. When boosting, a limiter keeps loud parts from distorting. Hold the row to set it back to 0. Android 9+ uses Android's dynamics effect; where it is not available, the preamp lifts or lowers the equalizer bands.
- Equalizer (Settings → Arsound → Equalizer): 21 presets, as in popular players ("Bass boost", "Vocal", "Rock", "Electronic", "Jazz", "Lo-fi" and others), in a drop-down list with a picture of the curve for each, and a curve whose points you drag with your finger. You can save your own setting under your own name — it appears in the same list under "My presets", where it can be renamed or deleted. Changes the sound immediately, without a restart. Works through Android's equalizer.
- Arsound's windows (questions, list choices, text input) are styled in the colours of the chosen theme.
- Listening stats (Settings → Arsound → Listening statistics): how much time you listened, the most frequent tracks and artists over 7 days, 30 days, a year or all time. A listen counts from 30 seconds; a pause does not split a listen into parts, even if the app was closed while paused. Works without internet too: track names come from the phone's memory. The artist is taken from a title like "Artist - Track", otherwise it is the uploader; different spellings of one artist ("CUPSIZE", "Cupsize") are counted together. Stored on the phone only; collection can be turned off and the history cleared.

**Other**
- Battery saving: fewer background requests.
- On first launch Arsound greets you: what the app is, where its settings are (Library → the gear in the top right → the topmost item "Arsound"), and which permissions it will ask for (notifications, music) and what stops working without each. Any of them can be declined. The greeting opens in English; the "EN / RU" switch in its bottom left corner changes its text at once, and when it is closed the whole app switches to the chosen language, without a restart (later it can be changed in Settings → Arsound → Appearance). The "Phone" permission that the original SoundCloud asked for is removed: the system pauses playback on a call anyway.
- Installs next to the original SoundCloud, with its own icon.
- Custom appearance (Settings → Arsound → Appearance): the themes "Scarlet" (Алый), "Cobalt", "Mint", "Sakura", "Lime" and "Winter" change the colours of the dark and light theme, the accent, the font, the rounding of covers and the mini-player, the splash colour and the loading animation. Each theme changes more than colours: its own icons, tab bar and mini-player, home greeting and glow, a "Your likes" strip in the theme's colour, icons in the Library, a glowing Play button in the player and playlists, and its own look of the Arsound settings. The app and Arsound screens are always dark, even if the phone uses the light theme; "Scarlet" is almost black with a red tint, font Golos Text. "SoundCloud" brings back the standard look. Themes are picked from a drop-down list where every theme shows its colours and animation. The app restarts after you choose.
- Each theme has its own light background animation: smouldering sparks for "Scarlet", stars with meteors for "Cobalt", fireflies for "Mint", falling petals for "Sakura", a scanner and shapes for "Lime", snowfall with turning crystals and golden glints and frost with icicles along the top edge for "Winter". It runs on the home screen and in the Arsound settings, freezes in battery-saving mode and starts again as soon as it is turned off, and can be turned off or slowed down in the same "Appearance" section.
- Without Google Play services (Huawei phones with HarmonyOS, phones without Google) the app no longer shows "Device not compatible" on every start: sign-in (through the browser), search, playback, downloads and themes work without them.
- The SoundCloud interface is translated into Russian. The app language is chosen in the same "Appearance" section: as in the system, Russian or English. The home screen mixes arrive from the SoundCloud server in English.
- The icon can be changed: 141 variants — eight new ones (tile, letter, glow, deep, flowers, kawaii, night, sticker) in the six theme colours, solid, gradients, iridescent, a coloured letter, glowing and neon letters, space, glacier, paint splashes, comic, matrix, chrome and others, as well as an icon in the wallpaper colours (Android 12+), and SoundCloud's own 14 icons (the orange original, OG, black, chrome, tie-dye, leopard and others) (Settings → Arsound → App icon). Some launchers remove the shortcut from the home screen on a change — it can be brought back from the app list.
- Arsound update check: when a new version is out, the app offers to download the APK from the Telegram channel or the patches from GitHub. It downloads and installs nothing by itself.
- Logs for the developer: the playback log is always written and kept for two days, the detailed mod log is turned on separately (at most 2 MB). If something breaks — Settings → Arsound → Developer → "Save the logs": both logs are saved as one file in "Downloads" (if the app crashed, the file starts with the error that closed it and with how Android says the app's last processes ended), the app shows the path to the file and offers to send it.

## How to install

The easiest way is to download the ready APK from the Telegram channel [t.me/arsound_git](https://t.me/arsound_git) and install it.

Or build it yourself right on the phone, without a computer, through [ReVanced Manager](https://github.com/ReVanced/revanced-manager/releases):

1. Install [ReVanced Manager](https://github.com/ReVanced/revanced-manager/releases/download/v2.6.0/revanced-manager-2.6.0.apk)
   and [the downloaders for it](https://github.com/ReVanced/revanced-manager-downloaders/releases/download/v1.2.0/revanced-manager-downloaders-1.2.0.apk).
2. In Manager: "Patches" → ✏️ → "Add patches" → "Enter URL" → paste
   `https://raw.githubusercontent.com/Allvoid/arsound/main/patches.json`.
3. "Apps" → SoundCloud → "Patch". Manager downloads the right SoundCloud version, patches it and offers to install.

A detailed guide with an explanation of every step is in the description of the [latest release](https://github.com/Allvoid/arsound/releases/latest).
You don't need to uninstall the original SoundCloud — Arsound installs as a separate app.

To build the patches yourself or patch on a computer — [BUILDING.md](BUILDING.md) (in Russian).

## FAQ

- **"Not available in your country", although the track plays in the original app.** Open Settings → Arsound → Updates and data →
  "Reset SoundCloud data". Your account sign-in, Arsound settings and imported music are kept.
- **Android asks "Open with: SoundCloud / Arsound".** Choose Arsound.
- **A white placeholder with no title on a track** — it is a subscriber-only track.

## License

**GNU General Public License v3.0**, see [LICENSE](LICENSE).

Based on [ReVanced Patches](https://gitlab.com/ReVanced/revanced-patches) © ReVanced, GPLv3.
Taken from ReVanced, unchanged or with small changes: the shared extension library (`extensions/arsound-shared`),
extension hookup and resource lookup (`patches/shared/misc`), bytecode utilities (`util`),
the package-name change patch (`patches/all/misc/packagename`), Gradle build settings. The telemetry patch is reworked
from ReVanced's patch of the same name. The rest in `patches/soundcloud` and `extensions/arsound` was written for this project.

SoundCloud is a trademark of SoundCloud Global Limited & Co. KG. The project does not distribute SoundCloud's APK or code.
