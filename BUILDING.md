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
| **Download tracks** | Сначала авторский download (`/tracks/{id}/download`), затем поток `progressive` из `media.transcodings` через DownloadManager. HLS не сохраняется, Go/Go+ и preview отсекаются до запроса ссылки. Уже скачанные и скачивающиеся треки повторно не ставятся в очередь. |
| **Offline first playlists** | Сохранённая копия плейлиста отдаётся сразу, синхронизация уходит в фон; фоновое сохранение метаданных всех плейлистов библиотеки (`SyncInitiator`). |
| **Play downloaded files** | Скачанный трек играет из файла (`Stream$FileStream`): элемент воспроизведения собирается сразу в `PlaybackMediaProvider.j`, без ожидания `TrackRepository` (`SYNC_MISSING`), метаданные уведомления ждут не больше 2 с. Таймаут обложки уведомления, повтор при сбое потока. Разрешение `READ_MEDIA_AUDIO` для файлов, скачанных до переустановки. |
| **Network** | Свой DNS (DoH/UDP) для всех клиентов OkHttp, блокировка запросов к SoundCloud с российского IP (проверка через Cloudflare), вопрос перед проверкой устройства DataDome, значок статуса сети над плеером. |
| **Local music** | Импорт файлов, плейлист «Импортированные», локальные добавления в любые плейлисты, свой порядок плейлистов в библиотеке и треков в плейлисте (`DragReorder`, порядок применяется к `playlistTrackUrns`). |
| **Power saving** | Реже опрос входящих, без фоновых отчётов SDK, без WifiLock при проигрывании файла. |
| **Hide duplicate recommendations** | Фильтр перезаливов в автовоспроизведении и на серверной главной (`SDUIView`). |
| **Hide subscription offers** | Экран покупки Go/Go+, окна MoEngage, вкладка Upgrade, серверные блоки главной `UpsellPlaceholder` и `BannerAdPlaceholder`, кнопка «Get Pro» в шапке. |
| **Change account type** | Свой тип аккаунта Android, чтобы мод работал рядом с оригиналом. |
| **Custom app name**, **Arsound branding** | Название, иконки (и 93 варианта иконки на выбор), анимации запуска и загрузки, логотипы и заглушка обложки. |
| **Change package name** | Патч ReVanced: пакет `com.soundcloud.android.revanced`. |

## Что нужно для сборки

- **Windows** (скрипт сборки — `.cmd`; на других системах команды те же, см. внутри скрипта).
- **JDK 21**. Скрипт по умолчанию ищет Eclipse Temurin в `C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot`,
  другой путь задаётся переменной `JAVA_HOME`.
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
  apk/soundcloud-2026.09.02-merged.apk   APK SoundCloud (закрытое приложение, публиковать нельзя)
  tools/revanced-cli-6.0.0-all.jar       ReVanced CLI
  tools/APKEditor-*.jar                  склейка split APK
  tools/apktool_*.jar, tools/jadx/       разбор APK
  sc-revanced.keystore                   ключ подписи мода
  manager.keystore                       (необязательно) ключ, экспортированный из ReVanced Manager;
  manager.keystore.alias, .password      его псевдоним и пароль из Manager → Настройки → Импорт и экспорт.
                                         Если он есть, сборка ставится поверх версии из Manager без потери данных
  lastfm.properties                      (необязательно) api_key=... — ключ Last.fm для плейлиста «Для вас»
                                         (last.fm/api/account/create). Без него плейлист не собирается.
                                         Вместо файла можно задать переменную ARSOUND_LASTFM_API_KEY
  out/                                   готовые APK и логи
```

**Ключ подписи нельзя терять и нельзя публиковать.** Android ставит обновление только поверх приложения
с той же подписью. Без ключа новую сборку не поставить поверх установленной — придётся удалять мод вместе с данными.

Инструменты:
[ReVanced CLI](https://github.com/ReVanced/revanced-cli/releases),
[APKEditor](https://github.com/REAndroid/APKEditor/releases),
[apktool](https://github.com/iBotPeaches/Apktool/releases),
[jadx](https://github.com/skylot/jadx/releases).

### Как достать APK SoundCloud

Проще всего с телефона, где SoundCloud установлен из Google Play:

```bash
adb shell pm path com.soundcloud.android
```

Команда выведет пути к `base.apk` и `split_config.*.apk`. Каждый скачивается через `adb pull <путь> local/apk/2026.09.02/`.
Google Play отдаёт приложение несколькими файлами, их нужно склеить в один:

```bash
java -jar local/tools/APKEditor-1.4.9.jar m -i local/apk/2026.09.02 -o local/apk/soundcloud-2026.09.02-merged.apk
```

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

Анимация фона темы (`theme/ThemeEffect.java`) повторяет макет «Анимации тем»: частицы считаются от времени, как
CSS-кадры, рисуются на Canvas и идут только пока видны. Эффект выбирается по id темы (`scarlet`, `cobalt`, `mint`,
`sakura`, `lime`); новой теме можно отдать чужой эффект полем `"effect"` в `themes.json`.

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
  (кнопки плеера и большая Play плейлистов), `homeGreeting` (приветствие и свечение на главной). Конвертер берёт все
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
- **Сразу после запуска Android блокирует сеть приложению, которое ещё не на экране**, и кэширует неудачный
  DNS-ответ на несколько секунд. Сетевые проверки при старте нужно начинать после выхода на передний план.

## Обновление под новую версию SoundCloud

1. Достать новый APK и склеить его (см. выше).
2. Собрать и попробовать пропатчить. CLI напишет, какие патчи упали, например
   `SEVERE: "Settings" failed`, со стеком. Строка с `patches/soundcloud/...` показывает, какой метод не нашёлся.
3. Открыть APK в jadx и найти, куда переехал нужный код: по строкам, по именам классов, по соседним вызовам.
   Для точной картины байткода удобно разобрать APK: `java -jar local/tools/apktool_3.0.3.jar d -r <apk>`.
4. Поправить поиск методов и версию в `compatibleWith(...)`, пересобрать.
5. Проверить на телефоне: лог расширения виден через `adb logcat | findstr revanced`.
