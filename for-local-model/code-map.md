# Code map (EXT packages)

| package | does | start |
|---|---|---|
| ads | blocks player ads | PlaybackAdPatch |
| analytics | telemetry off | DisableTelemetryPatch |
| branding | app icon choice | AppIcons |
| debug | save logs, crash log, playback timeline | LogExport, CrashLog |
| debug/sections | parts of the log file | LogSection, *Section |
| download | track/playlist downloads, index, folder (DRM part: do not edit) | DownloadIndex, DownloadButtons |
| library | hides import banner | HideImportBannerPatch |
| local | imported files, "Imported" playlist, own additions/order, merge, likes with files | LocalMusic, LocalAdditions, ImportedLikes |
| login | "Save logs" on sign-in screen | LoginLogsButton |
| lyrics | LRCLIB synced lyrics in full player | LyricsHooks, LyricsLayer, LyricsRepository |
| lyrics/transcribe | on-device Whisper (sherpa-onnx), downloaded on enable | TranscriptionManager, TranscriptionJob |
| network | custom DNS, IP-country block, net badge, DataDome prompt | NetworkPatch, RegionGuard |
| offline | saved data first, play downloaded file, retry | OfflineFirstPatch, InstantFilePlayback |
| permissions | first-run greeting, permission notes, language switch | WelcomePermissions |
| player | equalizer, sleep timer, play mode, stats, stream cache, vivo card | AudioEqualizer, SleepTimer, PlayModeButton |
| power | power saving | PowerSavingPatch |
| recommendations | duplicate filter, dislikes, "For you" | DuplicateFilter, ForYou, TrackDislikes |
| region | "?" badge on "not available in your country" | RegionHint |
| screenshot | screenshot share dialog | ScreenshotShare |
| search | YouTube Music search under search bar | SearchSourceSwitch, OtherSource |
| settings | setting values + all screens | Settings, BoolSettings, SettingsScreens, SettingsUi |
| settings/search | settings search engine (stems, synonyms, typos) | SearchIndex, SynonymTable |
| shared | helpers | Lang, Reflect, ArsoundDialog |
| theme | themes, colours, fonts, start screen | ArsoundTheme, ThemeLifecycle |
| theme/effect | animated theme backgrounds | EffectKind, EffectScene |
| update | update check + ScreenHooks registry | UpdateChecker, ScreenHooks |
| upsell | hides subscription offers | HidePaywallPatch |
| wallpaper | wallpaper behind main screens, Wallhaven | WallpaperSurfaces, WallpaperStore |
| widget | widget heart colour, likes widget covers | WidgetHeart, WidgetImages |

Big facades (logic lives in sibling classes): LocalAdditions, ArsoundTheme, ReVancedSettingsActivity (only picks a screen).
Not ours (do not refactor): `extensions/arsound-shared`, `PAT/../util`, `patches/.../shared`, `patches/.../all`.
