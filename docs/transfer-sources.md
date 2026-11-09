# Перенос из Яндекс Музыки, VK и YouTube Music: откуда берутся треки

Экран «Перенос из других сервисов» (Настройки → Arsound → Своя музыка) читает список треков из одного из трёх
сервисов и дальше делает одно и то же для всех: ищет каждый трек в SoundCloud, собирает найденные в приватный
плейлист, по галочке лайкает. Общая часть — `TransferJob`, `TrackSearch`, `SoundCloudSide`, `TransferStore`; у
каждого сервиса своя реализация `TransferSource` (`YandexSource`, `VkSource`, `YouTubeMusicSource`): ссылка, вход,
списки аккаунта, чтение коллекции. Ссылка в окне «Вставить ссылку» сама определяет сервис
(`TransferSource.findLink`); строка «Сервис» в части «Ваш аккаунт» выбирает, чей аккаунт показан (настройка
`transfer_source`).

Ключи. Коллекция помнится по ключу (`TransferLink.key()`): у Яндекса прежний вид `PLAYLIST:owner:id` (история и
прерванный перенос старых версий читаются как раньше), у остальных с приставкой: `vk:PLAYLIST:owner:id:accessKey`,
`vk:AUDIOS:owner::`, `ytm:PLAYLIST::id:`, `ytm:LIKES:::`. Найденные совпадения (`matches.json`) — по id трека:
голый номер у Яндекса, `vk:<owner>_<id>`, `yt:<videoId>` (у недоступного видео — `yt?:` и имя).

## YouTube Music

Проверено вживую (ноябрь 2026, без аккаунта).

- Запрос: `POST https://music.youtube.com/youtubei/v1/browse?prettyPrint=false`, тело
  `{"browseId": "VL<id плейлиста>", "context": {"client": {"clientName": "WEB_REMIX", "clientVersion": "1.20260121.03.00", "hl": "en"}}}`,
  заголовки `Origin`/`X-Origin: https://music.youtube.com`, браузерный `User-Agent`.
- Публичный плейлист или альбом (`music.youtube.com/playlist?list=PL…`, `OLAK5uy_…`, `RDCLAK5uy_…`,
  `youtube.com/playlist?list=…`, `watch?v=…&list=…`) читается без входа.
- Треки: `musicPlaylistShelfRenderer` (у понравившихся — `musicShelfRenderer`) → `contents[].musicResponsiveListItemRenderer`:
  `flexColumns[0]` — название (и `watchEndpoint.videoId`, `musicVideoType`), `flexColumns[1]` — исполнители (ссылки
  на артиста), `fixedColumns[0]` — длина «3:55», `playlistItemData.videoId`. Название плейлиста —
  `musicResponsiveHeaderRenderer.title`.
- Страницы: последний элемент `continuationItemRenderer.continuationEndpoint.continuationCommand.token` →
  `POST browse {"continuation": "<token>"}` → `onResponseReceivedActions[].appendContinuationItemsAction.continuationItems`
  (проверено: 100 + 82 = 182 трека). Старый вид (`continuations[].nextContinuationData`, `continuationContents`)
  тоже читается.
- Парсер (`YouTubeMusicParser`) ищет части по имени в любом месте дерева, а не по жёсткому пути: YouTube
  переставляет обёртки.
- Загрузки слушателей (`MUSIC_VIDEO_TYPE_UGC`) называют канал вместо исполнителя: исполнитель берётся из названия
  «Артист - Песня», приписки «(Official Video)», «[Lyrics]» и т. п. убираются.
- С аккаунтом (тот же вход, что в поиске Arsound, `YouTubeAccount`): cookie сессии +
  `Authorization: SAPISIDHASH …` + `X-Goog-AuthUser: 0`. Понравившиеся — список лайков `browseId: VLLM`
  (ссылка `list=LM` — он же; так же читает desktop; `FEmusic_liked_videos` — другой список, песни библиотеки), плейлисты библиотеки — `FEmusic_liked_playlists` (`gridRenderer` →
  `musicTwoRowItemRenderer`, `browseId` `VL…`; `VLLM` и `VLSE` пропускаются). Вход проверяется по
  `responseContext.serviceTrackingParams` → `logged_in`: ответ «не вошёл» на запрос с cookie = сессия устарела.
  С аккаунтом вживую не проверялось (чужой аккаунт использовать нельзя); формы ответа — по ytmusicapi и общему
  виду публичных ответов, в тестах синтетические фикстуры.
- Что ломает: смена `clientVersion` (YouTube долго принимает старые), смена названий рендереров, проверка на бота
  для сети. Миксы «RD…» (кроме `RDCLAK…`) не читаются: у них нет постоянного списка.

## VK

Публичного API музыки для сторонних приложений у VK нет: `audio.*` отвечает только приложениям самого VK. Без
входа VK не показывает музыку вовсе — даже публичный плейлист по ссылке (`m.vk.com/audio?act=audio_playlist…` отдаёт
пустую оболочку страницы; проверено вживую). Поэтому всё чтение — только после входа.

Как сделано (по устройству сайта VK; источник — расширение vkui-audiopad, `docs/ai/vkru-scraping.md`, ноябрь 2026):

1. Вход: `VkLoginActivity` открывает страницу VK `https://m.vk.ru/login` в WebView. Пароль вводится на странице VK;
   Arsound его не видит и не хранит. Сессия остаётся в cookie этого WebView (личное хранилище приложения).
2. После каждой страницы VK в фоне спрашивается ключ доступа самого сайта:
   `POST https://login.vk.ru/?act=web_token`, форма `version=1&app_id=6287487&access_token=`, с cookie сессии и
   `Origin: https://vk.ru`. Ответ `{"type":"okay","data":{"access_token","expires","user_id"}}` — вход выполнен;
   без сессии `{"type":"error","error_info":"unauthorized"}` (проверено вживую). Для сессии на vk.com — то же на
   `login.vk.com`. 6287487 — приложение самого сайта VK: только его ключ принимают методы музыки.
3. Ключ живёт в памяти, пока не истечёт (`expires`), и спрашивается заново; в настройках (`arsound_vk`) — только
   номер и имя вошедшего и домен сессии.
4. Методы: `POST https://api.vk.ru/method/<метод>?v=5.246&client_id=6287487`, форма с `access_token`:
   - `audio.get` (`owner_id`, `count`, `offset`; для плейлиста `playlist_id`, `access_key`) →
     `response.count`, `response.items[]` с `artist`, `title`, `duration` (с), `subtitle`, `main_artists[].name`,
     `featured_artists[].name`;
   - `audio.getPlaylists` (`owner_id`, `count`, `offset`) → `items[]` с `id`, `owner_id`, `title`, `count`,
     `access_key`, у подписанных копий `original{owner_id, playlist_id, access_key}` — треки читаются из оригинала;
   - `audio.getPlaylistById` — название плейлиста по ссылке; `users.get` — имя для строки «Выйти».
   - Ошибки: 5 — ключ не принят (спрашивается заново один раз, потом «вход устарел»), 6/9/29 — слишком часто
     (пауза и повтор), 15/18/30/2xx — нет доступа (закрыт, удалён, нет ключа доступа в ссылке).
5. Ссылки: `vk.com|vk.ru/music/playlist/<owner>_<id>_<ключ>`, `/music/album/…`, `audio?act=audio_playlist<owner>_<id>&access_hash=…`,
   `…?z=audio_playlist<owner>_<id>/<ключ>`, `/audios<owner>` (музыка человека или сообщества).

Вживую с аккаунтом VK не проверялось (входить в чужие аккаунты нельзя); форма ответов — по документации VK API и
наблюдениям vkui-audiopad, в тестах синтетические фикстуры. Что может сломать: VK закроет `act=web_token` для
WebView или привяжет ключ к origin/браузеру, сменит id приложения сайта, переведёт музыку на другие методы
(`catalog.*`). Тогда запасной путь — читать раздел «Моя музыка» через старый `al_audio.php` с cookie (его ещё
использует сам сайт), но он отдаёт HTML в windows-1251 и меняется чаще.

## Яндекс Музыка

Без изменений: API приложений Яндекс Музыки (`api.music.yandex.net`), публичное — без входа, закрытое — с токеном
со страницы входа Яндекса (клиент приложений Яндекс Музыки). См. `transfer/package-info.java`.
