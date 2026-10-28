# Rules

Never:
- Rename/change signature of an EXT method that a PAT file calls by string (`Lapp/revanced/extension/...;->name(...)`). Grep PAT first.
- Rename classes named in manifest/resources (Activities, Receivers) or change preference keys.
- Edit DRM work in progress: `download/ClientProfiles`, `DownloadTrackPatch`, `HlsDownloader`, `TrackSource`, `UnavailableTracks`, `DownloadPlaylistPatch`.
- Copy helpers. Use `shared/`: `Lang.text(ru,en)`, `Dimens.dp`, `Colors`, `Reflect`, `AtomicFiles`, `AppResources.identifier`, `Toasts`, `Rx`, `ArsoundDialog`.
- Use `switch` on a kind string; use an interface/enum with behaviour.

Do:
- Small classes, one job, short Javadoc saying who calls it.
- UI text: Russian first via `Lang.text("ru", "en")`.
- Pure logic in Android-free classes + JUnit 4 test.
- Settings: key+default once in `settings/BoolSettings`; screen = one `*Screen` class; rows via `SettingsUi`.
- Screen lifecycle reactions: register in `update/ScreenHooks` (order matters).
- Kotlin hooks: reuse `PAT/misc/hooks` (`insertGuard` + `GuardExit`).

Build/test (Windows):
- Unit tests: `gradlew :extensions:arsound:testDebugUnitTest`
- Build + patch + install: `build-and-install.cmd phone arm64` (or `emu`). Read the FIRST error only.
- Target SoundCloud version: `SOUNDCLOUD_VERSION` in `PAT/misc/SoundCloudTarget.kt`. Obfuscated names belong to it.
