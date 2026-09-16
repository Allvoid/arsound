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
build-and-install.cmd             Сборка, патчинг и установка одной командой
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
| **Control playback advertisements** | Флаг `no_audio_ads`, условия баннеров, реклама между треками и полноэкранная реклама при запуске. |
| **Offline first playlists** | Сохранённая копия плейлиста отдаётся сразу, синхронизация уходит в фон; фоновое сохранение метаданных всех плейлистов библиотеки (`SyncInitiator`). |
| **Play downloaded files** | Скачанный трек играет из файла (`Stream$FileStream`), таймаут обложки уведомления, повтор при сбое потока. |
| **Network** | Свой DNS (DoH/UDP) для всех клиентов OkHttp, блокировка запросов к SoundCloud с российского IP (проверка через Cloudflare), вопрос перед проверкой устройства DataDome, плашка статуса сети. |
| **Local music** | Импорт файлов, плейлист «Импортированные», локальные добавления в любые плейлисты, свой порядок плейлистов в библиотеке. |
| **Power saving** | Реже опрос входящих, без фоновых отчётов SDK, без WifiLock при проигрывании файла. |
| **Hide duplicate recommendations** | Фильтр перезаливов в автовоспроизведении и на серверной главной (`SDUIView`). |
| **Hide subscription offers** | Экран покупки Go/Go+, окна MoEngage, вкладка Upgrade. |
| **Change account type** | Свой тип аккаунта Android, чтобы мод работал рядом с оригиналом. |
| **Custom app name**, **Arsound branding** | Название, иконки, анимации запуска и загрузки, логотипы и заглушка обложки. |
| **Change package name** | Патч ReVanced: пакет `com.soundcloud.android.revanced`. |

## Что нужно для сборки

- **Windows** (скрипт сборки — `.cmd`; на других системах команды те же, см. внутри скрипта).
- **JDK 21**. Скрипт по умолчанию ищет Eclipse Temurin в `C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot`,
  другой путь задаётся переменной `JAVA_HOME`.
- **Android SDK** (достаточно `platforms;android-34` и build-tools), путь в `ANDROID_HOME`.
- **adb** и телефон с включённой отладкой по USB.
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
из `local/` и ставит на подключённый телефон. Если телефона нет, готовый APK остаётся в `local/out/`.

Иконки и анимации пересобираются отдельно:

```bash
python tools/branding/generate.py <папка экспорта иконки>
```

Нужны `pillow`, `picosvg`, `skia-pathops`, `resvg-py`. Заглушка обложки берётся из разобранного APK
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
