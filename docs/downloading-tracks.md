# Поиск Arsound: треки из YouTube Music

> **Как это сделано в Arsound.** Ниже — исходный план; реализация отличается в нескольких местах.
>
> - **Где в интерфейсе.** Вкладка «Поиск»: под строкой поиска переключатель «SoundCloud / Arsound»
>   шириной со строку поиска (по умолчанию SoundCloud); на половине Arsound рядом с названием маленький «?» — он открывает плашку
>   в том же виде, что приветствие при первом запуске. В режиме Arsound выдача SoundCloud скрыта,
>   вместо неё список найденного; поиск идёт сам, через 0,7 с после ввода.
> - **Строка результата.** Нажатие — прослушать (второе нажатие — стоп; SoundCloud встаёт на паузу),
>   кнопка справа — скачать, с процентами. Больше ничего: в плейлисты трек попадает только после скачивания.
> - **Исполнители и альбомы.** Вместе с треками ищутся альбомы (фильтр `music_albums`) и исполнители
>   (`music_artists`) — отдельными запросами параллельно; их ошибка только скрывает раздел. Исполнитель
>   открывается вкладкой «Релизы» своего канала (`ChannelTabs.ALBUMS`), альбом — как плейлист
>   (`PlaylistInfo`), со строкой «Скачать всё». Строка «← Назад» возвращает на прошлую страницу.
> - **Пакетное скачивание с компьютера.** `local/BatchActivity` берёт список `исполнитель<TAB>название`
>   из папки `Android/data/<пакет>/files/batch/` (другие приложения туда писать не могут), скачивает
>   только совпавшее по исполнителю и названию и дописывает в открывавшийся ранее плейлист:
>   `adb shell am start -n <пакет>/app.revanced.extension.soundcloud.local.BatchActivity --es file list.txt --es playlist "Название"`.
>   Итог по каждой строке — в `list.txt.report` (OK / MISS / FAIL); повторный запуск пропускает скачанное.
> - **Куда скачивается.** Не через `DownloadManager`, а прямо в папку импортированной музыки:
>   трек сразу в плейлисте «Импортированные» и играет в плеере SoundCloud. Файл `.m4a` (AAC, itag 140).
> - **Библиотека.** NewPipeExtractor v0.26.5 с JitPack, модуль `extensions/arsound/newpipe`:
>   вместе с зависимостями (jsoup, rhino, nanojson, protobuf) перенесена в пакет `app.arsound.shaded`,
>   потому что в SoundCloud уже есть свой `com.google.protobuf`. Сетевой слой — `HttpURLConnection`.
>   Код — `search/OtherSource.java` и `search/SearchSourceSwitch.java`.
> - **Поток читается только кусками.** YouTube отвечает 403 на поток без диапазона, поэтому и
>   скачивание, и прослушивание (через `MediaDataSource`) запрашивают части параметром `&range=`.
> - **Ссылка привязана к IP.** Если VPN выпускает запросы к youtube.com и к googlevideo.com с разных
>   адресов, поток отдаёт 403. Скачивание тогда трижды берёт новую ссылку; если не помогло, пишет,
>   что дело в VPN.
> - **Российский IP.** Настройка «Не выходить в сеть с российского IP» закрывает и этот поиск:
>   каждый запрос к YouTube сначала проходит `RegionGuard.throwIfBlockedAnyHost()`.

---

# Реализация поиска и скачивания музыки на Android (Kotlin)

Для создания легковесного, быстрого и надежного модуля для поиска и загрузки аудио с YouTube Music в нативном Android-приложении, рекомендуется использовать архитектуру на основе библиотеки **NewPipeExtractor**. Это позволяет избежать запуска внешних процессов (как `yt-dlp`) и работать напрямую с сетевым стеком Kotlin/Java.

Ниже приведено подробное описание общего метода реализации.

## 1. Выбор зависимостей и настройка проекта

Основной инструмент — `NewPipeExtractor`. Это open-source библиотека, которая содержит логику парсинга сайтов (YouTube, SoundCloud и др.), извлечения метаданных и получения прямых ссылок на медиапотоки.

### Настройка Gradle
В файл `build.gradle.kts` (Module: app) необходимо добавить репозиторий JitPack и зависимость extractor-core.

```kotlin
dependencies {
    // Ядро NewPipe для работы с YouTube
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.23.0") // Проверьте актуальную версию
    
    // Для обработки JSON (если не используете kotlinx.serialization или Gson)
    implementation("org.json:json:20240303") 
    
    // Coroutine support для асинхронных операций
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

> **Примечание:** Убедитесь, что версия `NewPipeExtractor` совместима с текущей структурой API YouTube. Библиотека часто обновляется, так как YouTube меняет свои внутренние эндпоинты.

## 2. Архитектура модуля

Модуль должен состоять из трех логических слоев:
1.  **Service Layer (Сервисный слой):** Инициализация `YoutubeService`, управление кэшем и жизненным циклом соединения.
2.  **Search Layer (Слой поиска):** Выполнение запросов, фильтрация результатов (только аудио), маппинг данных в модель приложения.
3.  **Download Layer (Слой загрузки):** Извлечение потоковых URL, выбор формата, передача задачи менеджеру загрузок ОС.

### Модель данных (Data Class)
Определите простую структуру для хранения информации о треке перед скачиванием.

```kotlin
data class TrackInfo(
    val id: String,          // YouTube Video ID
    val title: String,       // Название трека
    val artist: String,      // Имя исполнителя
    val durationMs: Long,    // Длительность в миллисекундах
    val thumbnailUrl: String,// Ссылка на обложку
    val videoUrl: String     // Полная ссылка на видео (для fallback)
)
```

## 3. Инициализация сервиса

`NewPipeExtractor` требует инициализации перед использованием. Это нужно сделать один раз при старте приложения (например, в `Application` классе или singleton-объекте).

```kotlin
object YoutubeManager {
    private var service: YoutubeService? = null

    fun init(context: Context) {
        if (service == null) {
            // Создаем экземпляр сервиса для YouTube
            service = YoutubeService.getInstance()
            
            // Опционально: установка User-Agent или прокси, если требуется
            // service.setUserAgent("Mozilla/5.0 ...")
        }
    }

    fun getService(): YoutubeService {
        return service ?: throw IllegalStateException("YoutubeManager not initialized")
    }
}
```

## 4. Реализация поиска (Search Logic)

Поиск выполняется через интерфейс `SearchEngineFactory`. Результатом является список объектов `InfoItem`, которые нужно преобразовать в вашу модель `TrackInfo`.

### Шаг 4.1: Создание запроса
Используем фильтр `FILTER_MUSIC` (если доступен в конкретной версии) или обычный текстовый поиск с последующей фильтрацией по типу контента.

```kotlin
suspend fun searchTracks(query: String): List<TrackInfo> = withContext(Dispatchers.IO) {
    val service = YoutubeManager.getService()
    
    // Получаем движок поиска
    val searchEngine = service.searchEngineFactory.create(service)
    
    // Выполняем поиск синхронно внутри IO dispatcher
    val items = try {
        searchEngine.search(query)
    } catch (e: Exception) {
        emptyList()
    }

    // Фильтруем и маппим результаты
    items.filterIsInstance<StreamInfoItem>() // Оставляем только стримы (видео/аудио)
         .mapNotNull { item ->
             try {
                 TrackInfo(
                     id = item.id,
                     title = item.name,
                     artist = item.uploaderName ?: "Unknown",
                     durationMs = item.duration * 1000L, // Перевод секунд в мс
                     thumbnailUrl = item.thumbnail?.url ?: "",
                     videoUrl = item.url
                 )
             } catch (e: Exception) {
                 null // Пропускаем поврежденные элементы
             }
         }
}
```

### Важные нюансы поиска:
*   **Пагинация:** `search()` возвращает первую страницу результатов. Для глубокого поиска реализуйте цикл с использованием `getMoreItems()`, если пользователю нужно листать вниз.
*   **Кэш:** Результаты поиска можно кешировать в памяти (LRU Cache) на короткое время, чтобы повторные запросы того же текста выполнялись мгновенно без обращения к сети.

## 5. Реализация получения потока (Stream Extraction)

После выбора трека пользователем, нужно получить прямую ссылку на аудиофайл. В `NewPipeExtractor` это делается через класс `StreamExtractor`.

### Шаг 5.1: Извлечение информации о потоках

```kotlin
suspend fun getAudioStreams(videoId: String): List<AudioStream> = withContext(Dispatchers.IO) {
    val service = YoutubeManager.getService()
    val url = "https://www.youtube.com/watch?v=$videoId"
    
    // Создаем экстрактор для конкретного видео
    val extractor = service.streamExtractorFactory.create(url)
    
    // Загружаем данные страницы/метаданные
    extractor.fetchPage()
    
    // Проверяем, доступна ли видео для просмотра (возрастные ограничения, регион и т.д.)
    if (!extractor.isVideoAvailable()) {
        throw AccessDeniedException("Video is not available in your region or restricted.")
    }

    // Получаем все доступные аудиопотоки
    val audioStreams = extractor.audioStreams
    
    // Сортируем по качеству (битрейт) или формату
    // Обычно лучший формат - Opus или AAC High Quality
    audioStreams.sortedByDescending { it.averageBitrate }
}
```

### Шаг 5.2: Выбор оптимального формата
Не всегда стоит брать самый высокий битрейт, если размер файла критичен. Можно предложить пользователю выбор или задать правило (например, "лучший MP3/AAC до 192kbps").

```kotlin
fun selectBestFormat(streams: List<AudioStream>): AudioStream? {
    // Приоритет 1: Opus (современный кодек, хорошее качество при малом размере)
    val opusStream = streams.firstOrNull { 
        it.format.contains("opus", ignoreCase = true) && it.bitrate >= 128_000 
    }
    if (opusStream != null) return opusStream

    // Приоритет 2: AAC/M4A (стандарт для iOS/Android плееров)
    val aacStream = streams.firstOrNull { 
        it.format.contains("aac", ignoreCase = true) || it.format.contains("m4a", ignoreCase = true)
    }
    if (aacStream != null) return aacStream

    // Fallback: Первый доступный
    return streams.firstOrNull()
}
```

## 6. Скачивание файла (Download Execution)

На этом этапе у вас есть объект `AudioStream`, содержащий поле `url` (временная ссылка на CDN YouTube). Сам процесс скачивания лучше делегировать системному `DownloadManager` Android, так как он устойчив к потере связи, работает в фоне даже после закрытия приложения и интегрируется с уведомлениями системы.

### Шаг 6.1: Подготовка запроса к DownloadManager

```kotlin
fun startDownload(context: Context, stream: AudioStream, fileName: String) {
    val request = DownloadManager.Request(Uri.parse(stream.url)).apply {
        setTitle(fileName)
        setDescription("Загрузка аудио...")
        
        // Тип MIME-кода зависит от формата (audio/mp4, audio/webm, etc.)
        setMimeType(stream.mimeType ?: "audio/*") 
        
        // Куда сохранять
        setDestinationInExternalPublicDir(
            Environment.DIRECTORY_MUSIC, 
            "$fileName.${getFileExtension(stream.format)}"
        )
        
        // Разрешить скачивание по мобильным данным (опционально)
        setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
        
        // Видимость в списке уведомлений
        setVisibleInDownloadsUi(true)
        
        // Обработка ошибок (повтор попыток)
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    }

    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    downloadManager.enqueue(request)
}

private fun getFileExtension(format: String?): String {
    return when {
        format?.contains("opus", true) == true -> "ogg"
        format?.contains("mp4", true) == true || format?.contains("aac", true) == true -> "m4a"
        else -> "mp3" // Default fallback name
    }
}
```

### Почему именно DownloadManager?
1.  **Фоновая работа:** Не блокирует UI поток.
2.  **Устойчивость:** Если Wi-Fi пропадет, система продолжит качать, когда связь восстановится.
3.  **Безопасность:** Нет необходимости писать свой сложный код возобновления загрузки (resume logic) с проверкой хешей и частей файлов.
4.  **Интеграция:** Файл сразу появляется в галерее/музыкальных плеерах устройства.

## 7. Обработка ошибок и граничные случаи

При работе с неофициальными API важно предусмотреть следующие ситуации:

1.  **Rate Limiting / Ban IP:** YouTube может временно ограничить частоту запросов.
    *   *Решение:* Внедрите экспоненциальную задержку (backoff strategy) при ошибках HTTP 429 или 403. Кешируйте результаты поиска дольше.
2.  **Изменение структуры API:** YouTube периодически меняет имена параметров или сигнатуры методов в своих внутренних скриптах.
    *   *Решение:* Регулярно обновляйте версию `NewPipeExtractor`. Логируйте ошибки парсинга (`ParseException`), чтобы быстро диагностировать изменения.
3.  **DRM-защищенный контент:** Некоторые треки могут быть защищены DRM и недоступны для прямого скачивания.
    *   *Решение:* `StreamExtractor` обычно помечает такие потоки как недоступные или выбрасывает исключение. Обрабатывайте это, показывая пользователю сообщение "Трек защищен и недоступен для скачивания".
4.  **Региональные блокировки:** Трек может быть заблокирован в стране пользователя.
    *   *Решение:* `isVideoAvailable()` вернет false. Предложите пользователю включить VPN или найдите альтернативную запись того же трека через расширенный поиск.

## 8. Оптимизация производительности

*   **Диспетчереры корутин:** Все сетевые операции (`fetchPage`, `search`) должны выполняться строго в `Dispatchers.IO`.
*   **Lazy Loading:** Не загружайте полные мета-данные всех результатов поиска сразу. `NewPipeExtractor` позволяет получать базовую информацию (`InfoItem`) быстро, а детальные потоки (`StreamInfo`) запрашивать только при клике на конкретный трек.
*   **Прокси (Опционально):** Если вы планируете раздавать приложение широкому кругу лиц, рассмотрите возможность добавления настройки прокси-сервера в `YoutubeService`, чтобы распределить нагрузку и обойти региональные блокировки.

## Резюме потока выполнения

1.  Пользователь вводит текст -> Вызывается `searchTracks()`.
2.  `NewPipeExtractor` делает GET-запрос к поисковой выдаче YouTube.
3.  Ответ парсится, формируется список `TrackInfo`.
4.  Пользователь выбирает трек -> Вызывается `getAudioStreams()`.
5.  `NewPipeExtractor` анализирует страницу видео, находит прямые ссылки на аудио-сегменты.
6.  Выбирается лучший формат (Opus/AAC).
7.  Ссылка передается в `DownloadManager`.
8.  Система Android скачивает файл в папку `/Music/`.

Этот подход обеспечивает максимальную автономность приложения, высокую скорость работы и полный контроль над процессом без зависимости от сторонних серверов или тяжелых исполняемых файлов.