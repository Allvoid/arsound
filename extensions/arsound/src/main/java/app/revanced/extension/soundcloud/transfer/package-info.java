/**
 * Transfer from Yandex Music, VK and YouTube Music: a playlist, an album or likes become a private SoundCloud playlist.
 *
 * <p>User view: Settings → Arsound → Your music → "Transfer from Yandex Music, VK and YouTube Music"
 * ({@code settings.TransferScreen}). A link from a service's "Share" is pasted (the service is told by the link), or,
 * after signing in to the service picked in the "Service" row, one of the account's own lists is picked (closed likes,
 * private playlists). Arsound looks each track up on SoundCloud and makes a private playlist of the ones found, with
 * the same name and order (one more, numbered, per 500 tracks). With the tick "Like all the tracks found" they are
 * liked too, for those who listen through their likes. The tracks not found are listed and can be copied. It runs on
 * while the settings are closed, in the foreground service {@code shared.ForegroundWork} (a notification), and a
 * transfer the app was closed in the middle of is remembered in {@code running.json}, with the playlists it made, so
 * the screen offers to go on.
 *
 * <p>Entry points: none from patches. {@code YandexLoginActivity} and {@code VkLoginActivity} are declared in the
 * manifest by {@code local/LocalMusicPatch.kt}; the screen calls {@code TransferJob} and {@code TransferSource}.
 *
 * <p>Sources: {@code TransferSource} is one service: its links ({@code TransferLink}, a key per collection), its
 * collections ({@code SourceCollection} of {@code SourceTrack}) and its optional account with its lists
 * ({@code SourceList}). {@code YandexSource} (Yandex Music's API, the one its apps use: public collections need no
 * account; {@code YandexAccount} and {@code YandexLoginActivity}: the optional token from Yandex's own sign-in page,
 * with the client id of the Yandex Music apps). {@code YouTubeMusicSource} (InnerTube "browse" of the web client,
 * {@code YouTubeMusicParser}; a public playlist needs no account, the liked music and the library use the YouTube
 * Music sign-in of the search, {@code search.YouTubeAccount}). {@code VkSource} (VK's API with the access of VK's own
 * website, {@code VkAccount}: VK gives music only to a signed-in session, so even a link needs the sign-in at
 * {@code VkLoginActivity}; {@code VkParser}). Endpoints and what can break them: {@code docs/transfer-sources.md}.
 *
 * <p>Shared: {@code TrackSearch} (each track looked up, three at a time, in the order of the collection, pure),
 * {@code SoundCloudSide} (search with {@code local.SongChoice} and the playlist through SoundCloud's own
 * {@code PlaylistOperations.createNewPlaylist}), {@code TransferJob} (the run, one at a time) and
 * {@code TransferStore} (JSON in {@code files/arsound-transfer}: the match of every source track for 60 days, a miss
 * for 3, and the last 20 transfers with their missing tracks).
 *
 * <p>Likes: {@code SoundCloudSide.like} goes through {@code local.SoundCloudLikes}, SoundCloud's own
 * {@code LikeToggler}, one track at a time with a pause, from the last track to the first ({@code LikePlan}), so the
 * first track of the list ends on top of the likes; tracks already liked are skipped, which is also how a repeat goes
 * on from where it stopped. A like that was written but not sent stays pending in SoundCloud's database.
 *
 * <p>Gotchas: the Yandex token is kept in the private preferences {@code arsound_yandex} and sent only to
 * {@code api.music.yandex.net}; the VK session stays in the WebView's cookies, {@code arsound_vk} holds only who is
 * signed in, and VK's access is kept in memory. The keys of Yandex collections have no prefix (as before the other
 * sources), the others start with "vk:" or "ytm:". A closed Yandex profile shows empty likes to everyone else: that
 * is told apart from an empty list and reported as private. SoundCloud's search is asked three tracks at a time, with
 * pauses on errors; eight failures in a row stop the run as "no connection", found tracks or not. Each playlist is
 * written to {@code running.json} the moment it is made, so a failed run does not make it again. The screen is told of
 * progress at most every 150 ms, and the history is read and written under one lock, off the main thread.
 */
package app.revanced.extension.soundcloud.transfer;
