# Сборка Arsound

Этот файл для тех, кто хочет собрать патчи сам или доработать их. Если нужно просто поставить мод,
читайте [README](README.md).

## Как устроен проект

```
patches/                          Патчи (Kotlin)
  src/main/kotlin/app/arsound/
    patches/soundcloud/           Патчи SoundCloud
    patches/shared/misc/          Общие части ReVanced: подключение расширения, поиск ресурсов
    patches/all/misc/packagename/ Патч смены имени пакета из ReVanced
    util/                         Утилиты ReVanced для работы с байткодом
extensions/                       Код, который встраивается в приложение (Java)
  arsound/                        Экран Arsound, скачивание, хуки
  arsound/stub/                   Заглушки классов SoundCloud для компиляции
  arsound-shared/                 Общая библиотека расширений ReVanced
tools/branding/generate.py        Генерация иконок и анимаций Arsound
tools/branding/icons.py           Варианты иконки приложения (цвета и градиенты)
tools/branding/design_icons.py    Иконки из набора Claude Design в цветах тем
tools/branding/theme_fonts.py     Шрифты тем оформления (статичные начертания из шрифтов Google Fonts)
build-and-install.cmd             Сборка, патчинг и установка одной командой
start-emulator.cmd                Запуск эмулятора Android для проверки без телефона
local/                            Локальные файлы, в Git не попадают (см. ниже)
```

**Патч** (Kotlin) при сборке находит нужный метод в байткоде SoundCloud и вставляет в него вызов.
Вызов уходит в **расширение** (Java) — обычный Android-код, который добавляется в APK и делает всю работу:
рисует экраны, ходит в API, хранит настройки.

Пользователь видит семь групп «Arsound: …» из `patches/soundcloud/ArsoundPatch.kt`, все включены по умолчанию.
Групп несколько намеренно: ReVanced Manager предлагает ту версию приложения, с которой совместимо больше всего
видимых патчей во всех подключённых наборах. Внутри групп — скрытые патчи (без имени):

| Скрытый патч | Что внутри |
|---|---|
| **Settings** | Пункт «Arsound» в настройках SoundCloud (Compose-строка `ActionListItemKt.a`), экран Arsound на обычных View с ресурсами SoundCloud, проверка обновлений через GitHub Releases. |
| **Disable telemetry** | Отключение аналитики SoundCloud. |
| **Download tracks** | Сначала авторский download (`/tracks/{id}/download`), затем `progressive` через DownloadManager или AES-HLS через внутреннюю сборку сегментов; при отсутствии подходящего потока выполняется поиск профиля веб-плеера. Go/Go+ и preview отсекаются до загрузки, потоки FairPlay/Widevine не экспортируются. Уже скачанные и скачивающиеся треки повторно не ставятся в очередь. |
| **Offline first playlists** | Сохранённая копия плейлиста отдаётся сразу, синхронизация уходит в фон; фоновое сохранение метаданных всех плейлистов библиотеки (`SyncInitiator`) и копий их обложек файлами (`LocalCovers`, отдаются вместо ссылки в `Playlist.getArtworkImageUrl`). |
| **Play downloaded files** | Скачанный трек играет из файла (`Stream$FileStream`): элемент воспроизведения собирается сразу в `PlaybackMediaProvider.j`, без ожидания `TrackRepository` (`SYNC_MISSING`), метаданные уведомления ждут не больше 2 с. Таймаут обложки уведомления, повтор при сбое потока. Разрешение `READ_MEDIA_AUDIO` для файлов, скачанных до переустановки. |
| **Network** | Свой DNS (DoH/UDP) для всех клиентов OkHttp, блокировка запросов к SoundCloud с российского IP (проверка через Cloudflare), вопрос перед проверкой устройства DataDome, значок статуса сети над плеером. |
| **Region hint** | Значок «?» справа от пометки «Недоступно в вашей стране» (`RegionHint`): метку рисуют два пути, и в оба вставлено по два вызова — `noteLabels` (в списке типов меток есть `MetaLabelType.IconWithText` с иконкой-замком `ic_labels_geo_locked`, `GeoLabel`) и `appendBadge` (Compose, `MetaLabelKt.a`: к списку частей перед `FlowRow` добавляется `AndroidView` с `RegionHintBadge`) или `appendBadgeView` (View, `MetaLabel.n`: к списку видов перед Flow), плюс баннер страницы трека (`GeoBlockedBannerRendererKt.a`, `bannerBadge`). Значок покачивается раз в 4,2 с (`ShakeCurve`), по нажатию окно `RegionHintDialog` с галочкой «Больше не показывать»; настройка `region_hint`, по умолчанию включена. |
| **Local music** | Импорт файлов, плейлист «Импортированные», локальные добавления в любые плейлисты, свой порядок плейлистов в библиотеке и треков в плейлисте (`DragReorder`, порядок применяется к `playlistTrackUrns`). Импортированные файлы в «Ваших лайках» (`ImportedLikesPatch` + `ImportedLikes`): хуки на `RoomLikesReadStorage.pagedLiveLoadTrackLikes` (первая страница; смещения следующих страниц переводит `LikesPaging`, живой список — `LikesFeed`), `loadLikedTrackPlayItems` (очередь), число в шапке `TrackLikesPresenter`, сердечко (`LikedStatuses.isLiked`, `LikeToggler.toggleTrackLike`, `LikeHearts`: убранные из лайков файлы помнит `HiddenLikes`); в таблицу `Likes` и на сервер локальные urn не попадают, `RelatedLikersService.getUsersLikedTracks` их отбрасывает. Настройка `imported_in_likes`, по умолчанию включена. |
| **Power saving** | Реже опрос входящих, без фоновых отчётов SDK, без WifiLock при проигрывании файла. |
| **Hide duplicate recommendations** | Фильтр перезаливов в автовоспроизведении и на серверной главной (`SDUIView`). |
| **Hide subscription offers** | Экран покупки Go/Go+, окна MoEngage, вкладка Upgrade, серверные блоки главной `UpsellPlaceholder` и `BannerAdPlaceholder`, кнопка «Get Pro» в шапке. |
| **Change account type** | Свой тип аккаунта Android, чтобы мод работал рядом с оригиналом. |
| **Custom app name**, **Arsound branding** | Название, иконки (и 93 варианта иконки на выбор), анимации запуска и загрузки, логотипы и заглушка обложки. |
| **Change package name** | Патч ReVanced: пакет `com.arsound.app` (до 0.9.3 включительно был `com.soundcloud.android.revanced`). |

### Как устроен код расширения

Пакеты в `extensions/arsound/src/main/java/app/revanced/extension/soundcloud/` повторяют функции: `settings`,
`theme` (и `theme/effect`), `wallpaper`, `local`, `download`, `lyrics`, `player`, `search`, `network`, `region`,
`login`, `recommendations`, `debug` и т. д.

У **каждого** пакета есть `package-info.java`: что функция даёт пользователю, точки входа (какие методы вызывают
Kotlin-патчи и хуки жизненного цикла), главные классы, где лежат ключи настроек и подводные камни. Читать код пакета
лучше с него. Ниже карта всех пакетов:

| Пакет | Что делает | С чего начать читать |
|---|---|---|
| `ads` | блокирует рекламу в плеере | `PlaybackAdPatch` |
| `analytics` | отключает телеметрию SoundCloud | `DisableTelemetryPatch` |
| `branding` | выбор значка приложения (варианты, псевдонимы лаунчера) | `AppIcons` |
| `debug` | «Сохранить журналы», журнал падений, таймлиния запуска треков | `LogExport`, `CrashLog` |
| `debug/sections` | части файла журналов (шапка, вход, падения, плеер, logcat) | `LogSection` (в `debug`), затем нужный `*Section` |
| `download` | скачивание треков и плейлистов, индекс скачанного, папка; DRM-часть пока в работе, не править | `DownloadIndex`, `DownloadButtons` |
| `library` | прячет баннер импорта плейлистов | `HideImportBannerPatch` |
| `local` | импорт файлов, плейлист «Импортированные», свои добавления и порядок, слияние, лайки с файлами | `LocalMusic`, `LocalAdditions`, `ImportedLikes` |
| `login` | кнопка «Сохранить журналы» на экране входа | `LoginLogsButton` |
| `lyrics` | синхронный текст песни из LRCLIB (запасной источник — NetEase Cloud Music) в полноэкранном плеере | `LyricsHooks`, `LyricsLayer`, `LyricsRepository` |
| `lyrics/transcribe` | распознавание слов песни нейросетью Whisper на телефоне | `TranscriptionManager`, `TranscriptionJob` |
| `network` | свой DNS, блокировка по стране IP, значок сети, вопрос перед DataDome, общие обложки | `NetworkPatch`, `RegionGuard` |
| `offline` | сохранённое показывается сразу, скачанное играет из файла, повтор при сбое сети | `OfflineFirstPatch`, `InstantFilePlayback` |
| `permissions` | приветствие при первом запуске, объяснение разрешений, переключатель языка (имя историческое) | `WelcomePermissions` |
| `player` | эквалайзер, таймер сна, кнопка режима воспроизведения, статистика, кэш потока, обложка уведомления, плеер vivo | `AudioEqualizer`, `SleepTimer`, `PlayModeButton` |
| `power` | экономия энергии: реже опрос, без фоновых отчётов | `PowerSavingPatch` |
| `recommendations` | фильтр дублей, «Не для меня», плейлист «Для вас» | `DuplicateFilter`, `ForYou`, `TrackDislikes` |
| `region` | значок «?» у пометки «Недоступно в вашей стране» | `RegionHint` |
| `screenshot` | окно «Поделиться скриншотом» | `ScreenshotShare` |
| `search` | поиск Arsound по YouTube Music под строкой поиска (не поиск настроек) | `SearchSourceSwitch`, `OtherSource` |
| `settings` | значения настроек и все экраны настроек | `Settings`, `BoolSettings`, `SettingsScreens`, `SettingsEntry` |
| `settings/search` | движок поиска по настройкам, без Android | `SearchIndex` |
| `shared` | общие помощники: язык, размеры, цвета, Reflect, Rx, диалоги | `Lang`, `Reflect`, `ArsoundDialog` |
| `theme` | темы поверх интерфейса SoundCloud, цвета, шрифты, стартовый экран | `ArsoundTheme`, `ThemeLifecycle` |
| `theme/effect` | анимированные фоны тем (искры, звёзды, лепестки, снег) | `EffectKind`, `EffectScene` |
| `update` | проверка обновлений и реестр реакций на экраны (`ScreenHooks`) | `UpdateChecker` |
| `upsell` | прячет предложения подписки и вкладку Upgrade | `HidePaywallPatch` |
| `wallpaper` | обои за главными экранами, браузер Wallhaven | `WallpaperSurfaces`, `WallpaperStore` |
| `widget` | сердечко виджета плеера по цвету обложки, обложки виджета лайков | `WidgetHeart`, `WidgetImages` |

Правила, по которым код разложен:

- **Точки входа патчей не переименовываются.** Kotlin-патч вызывает метод расширения по строке вида
  `Lapp/revanced/extension/soundcloud/local/LocalAdditions;->tracksWithLocalFiles(...)`. Поэтому крупные классы
  остались тонкими фасадами с теми же публичными методами, а работа лежит в классах рядом (например,
  `LocalAdditions` → `AdditionsStore`, `LocalTrackLoader`, `LocalFileTracks`, `AdditionsMenus`).
- **Общие помощники — в `shared/`:** `Lang.text(ru, en)` (язык интерфейса), `Dimens.dp` (dp в пиксели),
  `Colors` (контраст, прозрачность, смешение), `Reflect` (поиск поля или метода по условию), `AtomicFiles`
  (запись через временный файл), `AppResources` (ресурс по имени с запасным пакетом `com.soundcloud.android`),
  `Toasts`, `Rx`, `ArsoundDialog`. Своих копий этих функций в классах быть не должно.
- **Экраны настроек** — по классу на экран (`AccountScreen`, `NetworkScreen`, `StorageScreen`…), список и порядок —
  в `SettingsScreens` и `SettingsLayout` (группы главного экрана), общие ряды и переключатели — в `SettingsUi`.
  Экран просто добавляет ряды (`SettingRow`) в список, а `SettingsPage` сама кладёт подряд идущие ряды в карточку
  (`SettingsGroup`); заголовок `createSubHeading` начинает новую карточку, параметры включаемой функции — `SubRows`.
  Поиск (`settings/search`: стеммер, синонимы `SynonymTable`, опечатки, раскладка) не зависит от Android и покрыт
  JUnit-тестами; ряды попадают в него сами, при сборке экранов. `ReVancedSettingsActivity` только выбирает
  экран. Булевы настройки описаны один раз в `BoolSettings` (ключ и значение по умолчанию); методы
  `Settings.isX()` оставлены для остального кода.
- **Варианты поведения — через полиморфизм**, а не через `switch` по строке: эффекты тем (`theme/effect/*Scene`),
  режимы таймера сна (`TimerMode`), DNS-резолверы (`DohResolver`, `PlainDnsResolver`), записи плейлиста
  (`FileEntry`, `TrackEntry`), части файла журналов (`debug/sections/*`), вид стартового экрана темы
  (`SplashLook` в `ThemePatch`).
- **Реакции на экраны** (`onActivityResumed`/`onActivityPaused`) собраны в `update/ScreenHooks`: порядок
  регистрации важен, сбой одной функции не роняет остальные.
- **В Kotlin-патчах** повторяющиеся вставки собраны в `misc/hooks`: `insertGuard` с видом выхода `GuardExit`
  (вызвать расширение и вернуть его ответ или продолжить оригинал), хуки на возвращаемое значение и конец
  конструктора. Версия SoundCloud, под которую написаны обфусцированные имена, — `SOUNDCLOUD_VERSION` в
  `misc/SoundCloudTarget.kt`.

Чистая логика без Android (подбор цветов, смещения страниц лайков, разбор текстов, маскировка секретов в журналах
и т. п.) покрыта JUnit-тестами в `extensions/arsound/src/test/java`. Запуск:

```bash
./gradlew :extensions:arsound:testDebugUnitTest
```

### Распознавание текста нейросетью

Пакет `lyrics/transcribe` слушает песни, текста которых нет в LRCLIB, моделью Whisper на телефоне (sherpa-onnx и ONNX
Runtime). Чтобы APK не вырос, **в него не входит ни нативная библиотека, ни модель**: они скачиваются при включении
функции в `files/arsound/asr` (только 64-битные ARM-телефоны, на других функции нет).

- **Что скачивается и откуда.** Библиотеки: официальный AAR `sherpa-onnx-static-link-onnxruntime` с GitHub Releases
  (из него вырезается `jni/<abi>/libsherpa-onnx-jni.so`, ONNX Runtime внутри него, 16 КБ-выравнивание проверено) и для
  отделения голоса ONNX Runtime 1.22.0 с Maven Central (`libonnxruntime.so` и `libonnxruntime4j_jni.so`): для
  `arm64-v8a` — сборка с QNN `onnxruntime-android-qnn` (на ядрах считает так же, как обычная, и умеет отдать модель
  нейропроцессору), для `x86_64` — обычная `onnxruntime-android` (у сборки с QNN нет x86-64); `<abi>` — `arm64-v8a`
  или `x86_64`. На Snapdragon с нейропроцессором (`Npu`: SM8550, SM8650, SM8750 — Hexagon V73, V75, V79) ещё
  `com.qualcomm.qti:qnn-runtime` 2.33.0 с Maven Central (версия, с которой собран ONNX Runtime 1.22.0): из AAR в 42 МБ
  вырезаются `libQnnHtp.so`, `libQnnHtpPrepare.so` (компилятор модели под нейропроцессор, 52 МБ), `libQnnSystem.so` и
  `libQnnHtpV<NN>Stub.so`/`Skel.so` только своего поколения. Модели: `csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16`
  (`model.int8.onnx`, `tokens.txt`), `csukuangfj/sherpa-onnx-whisper-small` и `-base` на Hugging Face (int8-энкодер,
  int8-декодер, `tokens.txt`), с **фиксированного коммита**, не с `main`; модель отделения голоса
  `UVR-MDX-NET-Voc_FT.onnx` из релиза `source-separation-models` sherpa-onnx. Всё качается по HTTPS с докачкой
  (`ResumableDownload`).
- **Проверка.** SHA-256 каждого файла записан в коде (`NativeArchive`, `AsrModel`, `VoiceModel`), а не берётся от сервера; файл с чужой
  суммой удаляется. Ссылки на другие хосты, кроме HTTPS, не принимаются, в том числе при перенаправлении.
- **Java-классы** sherpa-onnx лежат в `extensions/arsound/src/main/java/com/k2fsa/sherpa/onnx` (Apache-2.0, **того же
  тега**, что и библиотека: нативный код читает их поля по именам). Только классы, нужные `OfflineRecognizer`; от
  оригинала отличается `LibraryLoader` (ничего не грузит сам: библиотеку после проверки грузит `NativeLibs` через
  `System.load`). Java-классы ONNX Runtime 1.22.0 лежат в `extensions/arsound/src/main/java/ai/onnxruntime` (MIT, тег
  `v1.22.0`); в `OnnxRuntime.load` добавлено одно: путь к библиотеке берётся из свойства, которое ставит `OrtLibs`.
  Правила `-keep class com.k2fsa.sherpa.onnx.**` и `-keep class ai.onnxruntime.**` стоят в `extensions/proguard-rules.pro`.
- **Отделение голоса.** В Java-интерфейсе sherpa-onnx его нет (ни в 1.13.8, ни в 2.0.1), поэтому алгоритм
  `OfflineSourceSeparationUvrImpl` перенесён на Java (`MdxSeparator`, `MdxStft`, `RealFft`, `VocalsStream`): куски по 15 с
  с полями по 1 с, STFT с окном Ханна 6144 и шагом 1024, сегменты по 256 кадров. Перенос сверен с выводом sherpa-onnx на
  ПК (корреляция 0,99999999). Модель берёт размеры из своих метаданных; другая модель UVR с теми же полями тоже подойдёт.
- **Нейропроцессор (`Npu`).** Модель отделения голоса идёт на Hexagon через QNN в половинной точности
  (`enable_htp_fp16_precision`); размер батча, открытый в модели, фиксируется (`setSymbolicDimensionValue`), а
  `session.disable_cpu_ep_fallback` требует, чтобы весь граф ушёл на нейропроцессор (иначе всё — на ядра). Первый раз
  модель компилируется (24–40 с на Galaxy S25 Ultra) и сохраняется рядом с ней (`UVR-MDX-NET-Voc_FT.v79.qnn-2.33.0.onnx`,
  47 МБ), дальше грузится за 0,3 с. Если нейропроцессор модель не берёт или компиляцию дважды прервали, это
  запоминается (`npu-refused-<версия>`, `npu-compiling-<версия>` в `files/arsound/asr`), библиотеки QNN удаляются, и
  телефон остаётся на ядрах до следующего обновления приложения; если нейропроцессор упал посреди песни, её
  доделывают ядра. Две ловушки, без которых в приложении (в отличие от `adb shell`) ничего не работает: в манифесте
  должен стоять `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>` (его добавляет
  `LyricsPatch`; без него Android 12+ не даёт приложению вендорскую библиотеку FastRPC), а `ADSP_LIBRARY_PATH` должен
  начинаться с папки скачанных библиотек (`Npu` ставит его через `Os.setenv`; иначе FastRPC ищет
  `libQnnHtpV79Skel.so` только в системных папках). Замеры 29.10 на Galaxy S25 Ultra: сегмент 0,23 с против 2,8–5 с на
  4–8 ядрах; в приложении песня 2:09 отделяется за 8,1 с и распознаётся за 4,2 с (трек 3:25 на 4 ядрах: 286 с и
  10 с); на пяти песнях CUPSIZE WER 0,411 против 0,410 на ядрах (CER 0,240 и 0,244). NNAPI в этой
  сборке ONNX Runtime нет, GPU через QNN даёт неверный результат, XNNPACK и fp16 на ядрах медленнее, int8 портит голос.
  Преобразования Фурье `MdxSeparator` считает на двух вспомогательных потоках, пока модель работает над соседним
  сегментом. GigaAM быстрее всего в 4 потока (песня 2:51: 4,0 с; в 6 потоков 4,7 с, в 8 — 6,1 с).
- **Как обновить версии.** `python tools/asr/pin.py library <тег>` печатает константы библиотеки sherpa-onnx для `NativeArchive`;
  `python tools/asr/pin.py model small` (и `base`) печатает адрес, коммит, размеры и суммы файлов для `AsrModel`
  (порядок в `AsrModel`: энкодер, декодер, токены); `python tools/asr/pin.py sources <тег>` заново скачивает Java-классы.
  Потом поправить тег в `NOTICE.txt` рядом с ними, прогнать `./gradlew :extensions:arsound:testDebugUnitTest` и проверить
  на телефоне: «Модель нейросети» в настройках скачивается, песня без текста распознаётся.
- **Качество (проверка 29.10, 5 песен CUPSIZE).** С отделением голоса `GigaAM v3` даёт WER 0,41 (CER 0,24), без него 0,63; `small` на голосе 0,67, на целой песне 0,66–0,77 (две песни); на ПК отделение занимает около половины длины трека в 4 потока, на эмуляторе трек 3:17 целиком (отделение и распознавание) занял 3,5 минуты. Подробнее про Whisper на целых песнях: `small` даёт WER около 0,67 и CER около 0,37, `base` — WER 1,1–1,3 (мусор), поэтому по умолчанию `small`, а `base` подписан как быстрая модель с ошибками. Модель сама зацикливается на длинных окнах («17-19-19-…», «もう一回 もう一回 …») и на выбранном «auto» уходила в японский: поэтому `Loops` режет повторы, `TextCleaner` отбрасывает окна «в основном повтор» и «не тем письмом», `LanguagePolicy` верит только русскому, английскому и языку телефона (русский язык по умолчанию в русском приложении), а `SongListener` слушает зациклившееся окно ещё раз двумя половинами. Из настроек декодера в теге 1.13.8 есть только `tailPaddings` (стоит 300), предела токенов и штрафа за повторы нет.
- **Лицензии.** sherpa-onnx — Apache-2.0, ONNX Runtime, GigaAM, Whisper и Ultimate Vocal Remover — MIT; указаны в
  `README` и `NOTICE.txt`. Библиотеки Qualcomm QNN — Qualcomm AI Hub Model License; в репозитории их нет, телефон
  скачивает их сам с Maven Central.
- **Хранение.** Распознанные тексты лежат в `files/arsound/transcripts` (не в кэше, который система вправе очистить),
  по файлу на трек и модель, недоделанные продолжаются с того же окна. «Очистить кэш текстов» их не трогает.
- **Звук берётся только из разрешённого.** Импортированный файл, трек, скачанный Arsound, или поток, который отдаётся
  всем (`download/TranscriptionAudio`): отрывки, треки по подписке и всё, что закрыто DRM (FairPlay, Widevine,
  SAMPLE-AES), отвергаются до запроса звука, а `HlsPlaylist` отвергает такой плейлист ещё раз.

## Что нужно для сборки

- **Windows** (скрипт сборки — `.cmd`; на других системах команды те же, см. внутри скрипта).
- **JDK 21**. Скрипт всегда берёт Eclipse Temurin из `C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot`, если он
  установлен, даже когда `JAVA_HOME` указывает на другой JDK: под Oracle JDK (21.0.5, 18) подпись падает с
  `Invalid password for keystore entry Arsound`, хотя пароль верный. Без Temurin берётся `JAVA_HOME`.
- **Android SDK** (достаточно `platforms;android-34` и build-tools), путь в `ANDROID_HOME`.
- **adb** и телефон с включённой отладкой по USB или эмулятор (см. «Эмулятор» ниже).
- **GitHub CLI** (`gh`), авторизованный с правом `read:packages`. Gradle-плагин ReVanced лежит в GitHub Packages,
  и без токена сборка не скачает его:
  ```bash
  gh auth refresh -h github.com -s read:packages
  ```
  Токен в файлах не хранится: скрипт берёт его командой `gh auth token` при каждой сборке.

### Папка `local/`

Всё, что нельзя или не нужно публиковать, лежит в `local/` (она в `.gitignore`):

```
local/
  apk/soundcloud-2026.10.14-merged.apk   APK SoundCloud (закрытое приложение, публиковать нельзя)
  tools/revanced-cli-6.0.0-all.jar       ReVanced CLI
  tools/APKEditor-*.jar                  склейка split APK
  tools/apktool_*.jar, tools/jadx/       разбор APK
  arsound.keystore                       ключ подписи мода: BKS, запись «Arsound», сертификат CN=Arsound
  arsound.keystore.password              пароль ключа (один для хранилища и записи)
  lastfm.properties                      (необязательно) api_key=... — ключ Last.fm для плейлиста «Для вас»
                                         (last.fm/api/account/create). Без него плейлист не собирается.
                                         Вместо файла можно задать переменную ARSOUND_LASTFM_API_KEY
  out/                                   готовые APK и логи
```

**Ключ подписи нельзя терять и нельзя публиковать.** Android ставит обновление только поверх приложения
с той же подписью. Без ключа новую сборку не поставить поверх установленной — придётся удалять мод вместе с данными.

Новый ключ создаётся один раз (keytool из JDK, BouncyCastle берётся из revanced-cli):

```bash
keytool -genkeypair -keystore local/arsound.keystore -storetype BKS \
  -providerclass org.bouncycastle.jce.provider.BouncyCastleProvider \
  -providerpath local/tools/revanced-cli-6.0.0-all.jar \
  -alias Arsound -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Arsound" \
  -storepass <пароль> -keypass <пароль>
```

Пароль запишите в `local/arsound.keystore.password` без перевода строки.

Инструменты:
[ReVanced CLI](https://github.com/ReVanced/revanced-cli/releases),
[APKEditor](https://github.com/REAndroid/APKEditor/releases),
[apktool](https://github.com/iBotPeaches/Apktool/releases),
[jadx](https://github.com/skylot/jadx/releases).

### Как достать APK SoundCloud

Проще всего с телефона или эмулятора, где SoundCloud установлен из Google Play:

```bash
adb shell pm path com.soundcloud.android
```

Команда выведет пути к `base.apk` и `split_config.*.apk`. Каждый скачивается через `adb pull <путь> local/apk/2026.10.14/`.
Google Play отдаёт приложение несколькими файлами, их нужно склеить в один:

```bash
java -jar local/tools/APKEditor-1.4.9.jar m -i local/apk/2026.10.14 -o local/apk/soundcloud-2026.10.14-merged.apk
```

Весь код лежит в `base.apk`, в `split_config.<abi>.apk` — только библиотеки под процессор. Эмулятор получает из
Google Play `split_config.x86_64.apk`, телефон — `split_config.arm64_v8a.apk`. Сборка без arm64 на телефоне не
запустится, поэтому в папку перед склейкой кладутся обе части: такой APK работает и на телефоне, и на эмуляторе.

## Сборка и установка

```bash
build-and-install.cmd
```

Скрипт собирает патчи (`patches/build/libs/patches-<версия>.rvp`, версия — в `gradle.properties`), применяет их к APK, подписывает ключом
из `local/` и ставит на все подключённые устройства: телефон и запущенный эмулятор. Если устройств нет, готовый APK остаётся в `local/out/`.
Поставить только на одно из них:

```bash
build-and-install.cmd phone
build-and-install.cmd emu
```

Версия для китайских прошивок (`local/out/arsound-<версия SoundCloud>-china.apk`) — то же, но под именем пакета
`com.kugou.viper` (KuGou Concept), которое уже есть в списке плеера в шторке vivo OriginOS и плееров OPPO/realme.
Ставится рядом с обычной сборкой, с отдельным аккаунтом. Аргумент `china` сочетается с `phone` и `emu`:

```bash
build-and-install.cmd china
build-and-install.cmd emu china
```

APK для Telegram-канала собираются из SoundCloud только с библиотеками телефона (`soundcloud-<версия>-arm64-merged.apk`,
файл меньше) — аргумент `arm64`, тоже сочетается с `china`. `phone` без подключённого телефона просто оставляет APK в `local/out/`:

```bash
build-and-install.cmd phone arm64
build-and-install.cmd phone arm64 china
```

Иконки и анимации пересобираются отдельно:

```bash
python tools/branding/generate.py <папка экспорта иконки>
```

Варианты иконки для выбора в настройках описаны в проекте иконки (`palettes.py` и `fancy.py` там пишут
`export/palettes/palettes.json`; иконки со свечением, неоном и рисунком — картинки WebP, остальные векторные) и переносятся в патчи так:

```bash
python tools/branding/icons.py <папка экспорта иконки>/palettes/palettes.json
```

Скрипт пишет векторные слои каждой иконки в ресурсы патча и списки вариантов
(`misc/branding/AppIcons.kt`, `branding/AppIconList.java`); их руками не правят.

Иконки из набора Claude Design («Arsound Icons»: плитка, буква, свечение, цветы, каваи, стикер…) лежат исходниками в
`tools/branding/design-icons/<вариант>.svg`, нарисованные в алом. Скрипт делает каждую в цвете каждой темы (акцент
тёмной палитры из `themes.json`, новая тема сама добавит цвет): оттенок поворачивается к оттенку акцента с той же видимой
яркостью, так белая буква не теряется на мятном и лаймовом. Плитка растягивается на весь фоновый слой значка Android,
буква уходит на передний; слои — WebP 432 px. Значки встают в конец списков между пометками `design icons`, по группе
на цвет; к темам они не привязаны. `icons.py` при перезапуске вызывает этот скрипт сам.

```bash
python tools/branding/design_icons.py
```

### Темы оформления

Темы описаны в `patches/src/main/resources/soundcloud/theme/themes.json`. Патч кладёт его и шрифты в `assets/arsound/`
и делает заставку каждой темы; приложение подменяет ресурсы SoundCloud на лету, после выбора темы оно перезапускается.

Заставку (системный экран запуска Android 12+: буква, которая рисуется на плоском фоне) рисует система из ресурсов APK
ещё до кода приложения, поэтому во время работы её перекрасить нельзя — можно только выбрать готовый стиль
(`SplashScreen.setSplashScreenTheme`, Android 13+). Что именно печь для темы, описывает `SplashLook` в `misc/theme/SplashLook.kt`:
у обычной темы один стиль `Arsound.Splash.<id>` (тёмный фон и акцент темы), у Monet один стиль на цвета системной
палитры, а у «Своей темы» (`"runtime": "custom"`) — сетка. Её цвета лежат в `SplashGridColors`: 29 тёмных фонов и 27 цветов
буквы (точные #121212 и #FF5500 первыми, чтобы тема по умолчанию выглядела точно), на каждую пару свой стиль
`Arsound.Splash.custom_<фон>_<буква>` и по одной подкрашенной анимации на цвет буквы. Патч кладёт список цветов в
`assets/arsound/splash-grid.json`, и приложение (`theme/SplashStyle.java`) выбирает ближайшую пару к настоящим цветам темы
по расстоянию в OKLab (`SplashColors`, `SplashGrid`, тесты рядом). Сетку можно урезать: нужно поправить два списка в
`SplashGridColors` (без мягких цветов буквы стилей втрое меньше), больше ничего менять не надо. Выбор запоминается в
настройке `arsound_splash_style` и сверяется при каждом открытии экрана, поэтому заставка чинится сама после обновления.
Окно `LauncherActivity` после заставки рисует уже приложение: его фон — цвет `arsound_launch_background`, который
`ArsoundTheme` подменяет на тёмную поверхность темы точно.

Анимация фона темы (`theme/ThemeEffect.java`) повторяет макет «Анимации тем»: частицы считаются от времени, как
CSS-кадры, рисуются на Canvas и идут только пока видны. Эффект выбирается по id темы (`scarlet`, `cobalt`, `mint`,
`sakura`, `lime`, `winter`); новой теме можно отдать чужой эффект полем `"effect"` в `themes.json`.

**Новая тема из макета Claude Design — одной командой.** Компонент телефона в макете хранит вид в объекте токенов
`th` (bg, surface, surface2, deep, border, accent, pink, muted, text2, head, track, rc, coverBorder, mini, miniBorder,
tabbar). Скрипт читает его из `.dc.html` или прямо из handoff-архива, выводит из токенов всю тему, скачивает шрифты с
Google Fonts и пишет тему в `themes.json` (тема с тем же id заменяется, её название и описание сохраняются):

```bash
python tools/branding/theme_from_design.py "<макет-handoff.zip>" <id темы> --variant <вариант> --name-ru "<имя>" --name-en "<name>"
```

Вариант — имя условия в макете (`anime ? {...} : {...}`: `--variant anime` берёт первый объект, любое другое — второй).
Дальше обычная сборка. Картинки макета (места под арты) скрипт пока не переносит.

Тему, у которой есть только две палитры SoundCloud (первые темы), тот же скрипт переводит в этот шаблон без макета:
токены светлого и тёмного вида выводятся из её палитр, название, описание, палитры, шрифты и скругления остаются.

```bash
python tools/branding/theme_from_design.py --from-palette cobalt
```

**Из чего состоит тема:**

- `dark`, `light` — палитра SoundCloud (surface, primary, secondary, highlight, special, error, overlay, imageBorders,
  dialog). У тёмной темы (`darkOnly`) светлая палитра красит только то, что SoundCloud держит светлым на тёмном (круглые
  кнопки плеера); `darkOnly` держит приложение тёмным через тёмный режим приложения в Android 12+.
- `fonts` — файлы шрифтов по местам Söhne; `radii` — скругления обложек и мини-плеера.
- `colors` — любые другие цвета SoundCloud по имени: серая шкала в оттенке темы, цвет полосы «Твои лайки».
- `tokens` и `parts` — цвета макета и части, которые тема берёт. `tokensLight` — токены светлого вида: тогда каждый
  цветной файл части кладётся дважды, в `<тип>` (светлый) и `<тип>-night` (тёмный), и Android выбирает по режиму;
  файл с пометкой `arsound:one-look` (плеер, он тёмный всегда) берёт только тёмные цвета. Часть — папка `theme/parts/<часть>/<тип>/<имя>.xml`
  с ресурсами, общими для всех тем: в файлах `${accent}`, `${mini}`, `${bg@85}` (85 % непрозрачности) и `${theme}`
  заменяются значениями темы. Патч кладёт файл в приложение как `arsound_<тема>__<имя>`, приложение направляет на него
  ресурс SoundCloud `<тип>/<имя>`. Части: `lucideIcons` (иконки), `libraryIcons` (значки строк
  «Библиотеки», их SoundCloud не рисует — патч спрашивает значок у темы), `tabBar`, `miniPlayer`, `playerButtons`
  (кнопки плеера и большая Play плейлистов), `homeGreeting` (приветствие и свечение на главной), `playerSkin` (новый
  плеер: разметка страницы трека, верхней колонки кнопок, нижней панели и времени над волной). Часть из списка
  `OPTIONAL_PARTS` в `ThemePatch.kt` включается в настройках: патч пишет её ресурсы в `theme-resources.json` под ключом
  `_optional`, и приложение не подменяет их, пока переключатель выключен (`ArsoundTheme.isPlayerSkinEnabled`). Конвертер берёт все
  части из папки `parts`, так что новая часть сразу достаётся и новым темам. Файлы только одной темы лежат в
  `theme/overrides/<тема>/` и перекрывают файлы частей с тем же именем. Gradle сам пишет индекс файлов тем, списки
  вручную не ведутся. В разметке нужно сохранить id и классы элементов, которые ищет код SoundCloud; файлы с именем
  `arsound_...` — добавки, их используют другие файлы темы.
- `decor` — украшения того, что рисует сам Arsound: экран настроек (`settingsGlow`, `settingsBadge`, `settingsStrips`),
  цвет приветствия на главной (`homeHelloColor`, приветствие — класс `theme/HomeGreeting`) и вуаль над полосой
  «Твои лайки» (`shortcutScrim` вместо 70 % чёрного SoundCloud). `decorLight` — значения для светлого вида, они перекрывают `decor`
  (сейчас не видны: SoundCloud тёмный всегда, экраны Arsound — настройки, импорт — тоже держатся тёмными через
  `ArsoundTheme.forceDark`).

**Шрифты** режутся из файлов Google Fonts (лицензия OFL), переменных или статичных; набор берётся из `fonts` всех тем.
Имя `<шрифт>_t<NN>_<вес>` — буквы на NN сотых em плотнее, так делаются плотные заголовки из макета. Пересобираются
только шрифты, лежащие в папке (`theme_from_design.py` вызывает это сам):

```bash
python tools/branding/theme_fonts.py <папка с файлами шрифтов>
```

**Иконки** (часть `lucideIcons`) — набор Lucide (лицензия ISC) той же версии, что в макете, переведённый в векторные
иконки Android с именами, размерами и цветами иконок SoundCloud:

```bash
python tools/branding/theme_icons.py <папка пакета lucide-static 0.460.0>
```

**Обои за экранами SoundCloud.** Картинка (`wallpaper/WallpaperStore`, `WallpaperDrawable`) — фон окна главной
активности: `WallpaperSurfaces.onActivityPreCreated` ставит её до создания экранов и делает строку состояния
прозрачной. SoundCloud поверх окна закрашивает слои цветом поверхности, поэтому, пока обои включены, их снимают
(патч `WallpaperSurfacesPatch.kt`, входит в тему):

- `Fragment.performViewCreated` (имя не обфусцировано): после `onViewCreated` вызывается
  `WallpaperSurfaces.onFragmentViewCreated(view)`. Если вид лежит в контейнере `main_container` активности, а не в
  плеере, окне или шторке, `ScreenBackgrounds` обходит контейнеры (до 10 уровней, внутрь `RecyclerView`, `AbsListView`
  и Compose не заходит) и убирает непрозрачную заливку цветами поверхности, в том числе у верхних панелей
  (`AppBarLayout`, `Toolbar`: их цвет Material прячет в `MaterialShapeDrawable`, это различает `ViewKind`).
- `ThemeKt.a` и `ScaffoldKt.a` (Compose-обёртка каждого экрана SoundCloud): вызов `Colors.j(Composer)` заменён на
  `WallpaperSurfaces.screenSurface(composer)`, который отдаёт прозрачный цвет, если вид Compose лежит в
  `main_container` окна с обоями, и обычный цвет иначе. Цвет, который вызывающий передал сам, не трогается.
  `Colors.j` обфусцирован: при обновлении SoundCloud ищите статический метод `Colors`, который возвращает `J`,
  принимает `Composer` и читает цвет `dark_surface`.
- Включение и выключение обоев перезапускает `MainActivity` (`onActivityResumed`): уже построенные экраны остались бы
  с лишней заливкой или без неё. Картинка, затемнение и размытие перерисовываются без перезапуска.

Плеер, очередь, мини-плеер, нижняя панель, диалоги и шторки лежат вне `main_container` и остаются непрозрачными.
Каталог Wallhaven (`settings/WallhavenScreen`, `wallpaper/ThumbnailLoader`) показывает ячейку в два этапа: миниатюра (300 пикселей, ARGB_8888),
затем, когда прокрутка стихла на 400 мс, `SharpLoader` скачивает полный файл (`SharpPolicy`: не больше 6 МБ и сеть без счётчика трафика),
уменьшает его при декодировании не меньше чем до ячейки (`CoverCrop`) и кладёт JPEG в `cacheDir/wallhaven-grid` (`GridDiskCache`, до 150 МБ,
первыми уходят давно не открывавшиеся). Тесты: `CoverCropTest`, `SharpPolicyTest`, `GridDiskCacheTest`, `WallhavenRulesTest`.
Скачанный JPEG не больше 2560 по длинной стороне ставится обоями без повторного кодирования (`WallpaperKeep`); запечённая копия пишется с качеством 95.
Чистая логика (цвета поверхности, имена классов) проверяется тестами `SurfaceColoursTest` и `ClassNamesTest`.

Русский перевод SoundCloud лежит в `patches/src/main/resources/soundcloud/translation/` (строки и множественные формы,
которые SoundCloud переводит на другие языки).

Нужны `pillow`, `picosvg`, `skia-pathops`, `resvg-py`, для шрифтов — `fonttools`. Заглушка обложки берётся из разобранного APK
(`local/analysis/res-decoded`), если он есть.

Патчинг на компьютере без Manager:

```bash
java -jar revanced-cli-6.0.0-all.jar patch -bp patches-<версия>.rvp soundcloud.apk
```

Все группы включены по умолчанию и сами подключают смену имени пакета с нужными опциями.

### Файл для ReVanced Manager

`patches.json` в корне репозитория — описание последней версии в формате ReVanced API. Manager подключает его
по ссылке `https://raw.githubusercontent.com/Allvoid/arsound/main/patches.json` и сам проверяет обновления.
При выпуске новой версии обновите в нём `version`, `created_at` и `download_url`.

Классы патчей лежат в `app.arsound.*`, а расширения называются `arsound*.rve`: если взять имена ReVanced,
Manager грузит оба набора в одно пространство, и наши копии ломают официальные ReVanced Patches.

## Эмулятор

Чтобы проверять сборку без телефона, на компьютере работает эмулятор Android 16 с Google Play.
Экран у него как у тестового телефона (720×1560, плотность 300), поэтому координаты нажатий из заметок подходят и ему.
SoundCloud собран только под ARM, эмулятор переводит его код на x86 сам, это немного медленнее телефона.

Запуск (первый раз — около минуты, дальше несколько секунд: эмулятор при закрытии сохраняет снимок):

```bash
start-emulator.cmd
```

Когда подключены и телефон, и эмулятор, команды `adb` нужно адресовать: `adb -s emulator-5554 …` для эмулятора.

Эмулятор создаётся один раз. Нужны Android SDK Command-line Tools (`cmdline-tools/latest` в SDK) и включённая в Windows платформа низкоуровневой оболочки (WHPX):

```bash
cmdline-tools\latest\bin\android sdk install emulator system-images/android-36/google_apis_playstore/x86_64
cmdline-tools\latest\bin\avdmanager create avd -n arsound -k "system-images;android-36;google_apis_playstore;x86_64" -d pixel_7
```

Затем в `%USERPROFILE%\.android\avd\arsound.avd\config.ini` поставить:

```ini
PlayStore.enabled=yes
hw.gpu.mode=host
hw.keyboard=yes
hw.lcd.width=720
hw.lcd.height=1560
hw.lcd.density=300
hw.ramSize=4096
hw.cpu.ncore=4
disk.dataPartition.size=16G
```

В SoundCloud на эмуляторе нужно войти заново: вход хранится в системных аккаунтах Android, а без root их с телефона не достать.

## Нюансы

### Сборка и ReVanced CLI

- **Порядок `-O` важен.** Опция относится к патчу, указанному прямо перед ней. Поэтому в скрипте
  `-e "Change package name"` стоит последним, а его опции идут сразу за ним. Если вставить другой `-e` между
  ними, опции тихо уйдут не туда и установка упадёт с `INSTALL_FAILED_DUPLICATE_PERMISSION`.
- **`adb install` без `--user 0`** на некоторых прошивках ставит приложение ещё и во второй профиль
  (например, клонированный или рабочий профиль), и появляется лишняя копия.
- **R8 вырезает методы, которые вызывает только SoundCloud.** Например, `invoke()` у обработчиков нажатий
  (интерфейсы Kotlin `Function0`). Правило в `extensions/proguard-rules.pro` это запрещает.
  Без него — `AbstractMethodError` при нажатии.
- В расширении уже есть стандартная библиотека Kotlin, собственные заглушки `kotlin.*` не нужны и мешают.

### Байткод SoundCloud

- Код **обфусцирован**: у классов и методов имена вроде `a`, `F`, `x`. Искать нужный метод надёжнее по строкам,
  типам параметров и именам классов, которые R8 не переименовал. Короткие имена могут смениться в любой версии,
  в том числе у библиотек: например, `notifyItemMoved` у `RecyclerView.Adapter` в этой сборке называется `m`.
- **ID ресурсов не всегда подставлены числами.** В модулях SoundCloud ресурс читается как
  `sget …/R$string;->settings_troubleshooting`, поэтому поиск по числовому ID не находит ничего.
- **Регистры выше `v15` нельзя передать в обычный `invoke-static`.** Выход — `invoke-static/range`
  или сохранить значение заранее.
- **`p0` может переиспользоваться** под другой объект внутри метода. Вызов, которому нужен `this`,
  ставится в начало метода.
- Экран настроек SoundCloud написан на **Jetpack Compose**, и сам Compose обфусцирован. Экран Arsound сделан
  на обычных View — он переживает обновления лучше.

### Поведение приложения

- **Вход в мод при установленном оригинале.** Android не даёт двум приложениям один тип аккаунта,
  поэтому без патча «Change account type» мод падает сразу после входа.
- **«Недоступно в вашей стране», хотя в оригинале трек играет.** SoundCloud хранит правила доступа к трекам
  в своей базе. Если библиотека синхронизировалась, когда сервер считал вас в другой стране, блокировки
  остаются в кэше. Лечится кнопкой «Сбросить данные SoundCloud».
- **Экран подписки и «Oops… try again».** Экран покупки загружает цены из Google Play, а мод установлен не
  из Play. Патч подменяет переход на этот экран пустым прозрачным экраном.
- **Кнопка «Сохранить логи» на экране входа.** Настройки Arsound до входа недоступны, поэтому плашка добавляется
  поверх `AuthenticationActivity` из обратных вызовов жизненного цикла в `UpdateChecker` (`LoginLogsButton`),
  патч не нужен. Если на какой-то версии SoundCloud плашка не появится, запасной путь — вызвать
  `LoginLogsButton.onActivityResumed` из `AuthLandingFragment.onViewCreated` (отдельный патч).
- **Сразу после запуска Android блокирует сеть приложению, которое ещё не на экране**, и кэширует неудачный
  DNS-ответ на несколько секунд. Сетевые проверки при старте нужно начинать после выхода на передний план.

## Обновление под новую версию SoundCloud

1. Достать новый APK и склеить его (см. выше).
2. Собрать и попробовать пропатчить. CLI напишет, какие патчи упали, например
   `SEVERE: "Settings" failed`, со стеком. Строка с `patches/soundcloud/...` показывает, какой метод не нашёлся.
3. Открыть APK в jadx и найти, куда переехал нужный код: по строкам, по именам классов, по соседним вызовам.
   Для точной картины байткода удобно разобрать APK: `java -jar local/tools/apktool_3.0.3.jar d -r <apk>`.
4. Поправить поиск методов и версию в `compatibleWith(...)`, пересобрать. Короткие имена вроде `f`, `Q`, `E` R8 раздаёт
   заново в каждой версии: метод надёжнее искать по параметрам, типу результата или вызовам внутри, а в расширении —
   по сигнатуре или поведению (так найдены `Observable.merge` и методы `ItemDragCallback.DragHost`). Заглушки в
   `extensions/arsound/stub` тоже сверяются с новой версией: вызов метода, которого больше нет, падает только
   во время работы.
5. Проверить на телефоне: лог расширения виден через `adb logcat | findstr revanced`.
