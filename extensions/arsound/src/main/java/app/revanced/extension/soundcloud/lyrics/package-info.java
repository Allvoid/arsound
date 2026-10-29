/**
 * Synced lyrics in the full-screen player, found on LRCLIB, else on NetEase Cloud Music.
 *
 * <p>User view: a lyrics button in the player opens the words of the song behind the cover; with timings the line that
 * sings now is highlighted; a tap on the words pauses and plays, two quick taps on a line seek there. Lyrics are
 * cached on the phone. For a song without lyrics
 * the page can offer to recognise the words with a neural network (see {@code lyrics.transcribe}): it asks the language
 * of the song first ({@code SongLanguageDialog}), and recognised words carry a button to hear them again in another
 * language.
 *
 * <p>Entry points (Kotlin: {@code player/LyricsPatch.kt}):
 * <ul>
 * <li>{@code LyricsHooks.onBind(Object view, Object item)} - a player page is filled with a track (the layer is
 * created or updated).</li>
 * <li>{@code LyricsHooks.onSlide(View, float)} - the player slides open or shut (every frame).</li>
 * <li>{@code LyricsClock.onService(Object)} and {@code LyricsClock.onState(Object, Object)} - SoundCloud's media session
 * state (about twice a second); the clock counts the position forward between states.</li>
 * </ul>
 * Both run on the main thread very often and must not throw into the player, so they do little and catch everything.
 *
 * <p>Main classes:
 * <ul>
 * <li>{@code LyricsLayer} - the view of one player page (between cover and title); {@code LyricsListView} - the column
 * of lines; {@code LyricsManager} - state shared by up to three pages; {@code LyricsAi}/{@code AiPanel}/
 * {@code SongLanguageDialog} - the offer for the neural network.</li>
 * <li>{@code LyricsRepository} - memory, then {@code LyricsCache} (one JSON file per track, with the source), then
 * LRCLIB, then NetEase when LRCLIB has nothing or fails; one thread does lookups one at a time. {@code LyricsFinder}
 * (LRCLIB) and {@code NeteaseFinder} with {@code TrackQuery}, {@code QueryVariants}, {@code TitleCleaner},
 * {@code TextMatch}, {@code RecordScorer}, {@code RecordPick} pick the right record; {@code LrclibClient}/
 * {@code NeteaseClient}/{@code LyricsHttp}/{@code LrcParser} talk to the services.</li>
 * <li>{@code AiTranscripts} - turns a recognised {@code Transcript} into {@code Lyrics}.</li>
 * </ul>
 *
 * <p>Settings: {@code lyrics_enabled} (button shown, default on), {@code lyrics_visible} (off until the first tap),
 * {@code lyrics_ai_enabled}, {@code lyrics_ai_separate}; the language answered per artist is in the preferences
 * {@code arsound_song_languages}. The settings screen is {@code settings.LyricsAiRows}.
 *
 * <p>Gotchas: the title cleaning, matching and scoring classes are pure Java and covered by JUnit tests; keep them free
 * of Android. LRCLIB asks for few requests and an honest User-Agent ({@code LyricsHttp} spaces them). State of a page is
 * read by shape because SoundCloud's field names are obfuscated ({@code PlayerStateReader}).
 */
package app.revanced.extension.soundcloud.lyrics;
