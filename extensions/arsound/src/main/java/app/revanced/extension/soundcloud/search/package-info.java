/**
 * "Arsound" search: a second search source (YouTube Music) under the SoundCloud search field. Not the settings search,
 * which is {@code settings.search}.
 *
 * <p>User view: a switch "SoundCloud | Arsound" under the search field of the Search tab. In Arsound mode the user
 * finds tracks, albums and artists that SoundCloud does not let download, listens to a result with one tap and
 * downloads it with the button on the right; downloads become imported music (see {@code local}). An optional YouTube
 * Music sign-in (Google's own page, Arsound never sees the password) lets age-restricted tracks play.
 *
 * <p>Entry points: no bytecode patch. {@code SearchSourceSwitch.onActivityResumed/onActivityPaused} are screen hooks
 * wired in {@code update.UpdateChecker}; they find the search screen of the main activity and put a
 * {@code SearchScreen} on it. {@code YouTubeLoginActivity} is declared in the manifest by
 * {@code local/LocalMusicPatch.kt}. {@code OtherSource} is also used by {@code local.TrackFinder} (the batch download).
 *
 * <p>Main classes:
 * <ul>
 * <li>{@code SearchScreen}, {@code SourceToggle}, {@code ResultTabs}, {@code ResultRow}, {@code SearchSourceSwitch} -
 * the views; {@code PreviewPlayer} - the preview; {@code DownloadQueue} - what is downloading and done.</li>
 * <li>{@code OtherSource} - search and audio streams through NewPipeExtractor (module {@code extensions/arsound/newpipe});
 * all calls block, run them off the main thread.</li>
 * <li>{@code YouTubeWebPlayer}, {@code PlayerCipher}, {@code PlayerConfig}, {@code SimpleJsonParser},
 * {@code PoTokenWebView}, {@code BotGuardData} - the way YouTube Music's web player is asked for a stream (signature
 * deciphering and proof-of-origin tokens, ported from other open projects).</li>
 * <li>{@code YouTubeAccount}, {@code YouTubeSession}, {@code YouTubeLoginActivity}, {@code SignInOffer} - the account.</li>
 * <li>{@code PlayabilityVerdict}, {@code SearchFailure} - what a refusal or an error means and what to tell the user.</li>
 * </ul>
 *
 * <p>Settings: no key in {@code settings.Settings}. The YouTube cookies are in the private preferences
 * {@code arsound_youtube_account} and go only to youtube.com.
 *
 * <p>Gotchas: {@code PoTokenWebView} and {@code PlayerCipher} depend on YouTube's current scripts and break when
 * YouTube changes them. Keep the screen code on the main thread and the network code off it.
 * {@code SearchSourceSwitch} finds SoundCloud's search views by resource id ({@code search_coordinator},
 * {@code search_container}, {@code search_edit_text}) in the main activity only, so a SoundCloud update that renames
 * them hides the switch.
 */
package app.revanced.extension.soundcloud.search;
