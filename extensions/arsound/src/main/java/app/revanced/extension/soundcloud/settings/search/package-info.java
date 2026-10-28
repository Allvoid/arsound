/**
 * The search engine of the settings screen: pure Java text matching, no Android.
 *
 * <p>User view: typing on the main settings screen finds rows by their letters, word endings (Russian and English
 * stems), synonyms ("память" finds storage), typos, and text typed on the wrong keyboard layout; under the results a
 * "did you mean" line and neighbouring words are offered. Each hit says why it was found.
 *
 * <p>Entry points: none from Kotlin patches. The Android side, {@code settings.SettingsSearch},
 * {@code settings.SearchRecorder} and {@code settings.SettingsSearchIndex}, fills a {@code SearchIndex} with
 * {@code SearchEntry} rows while the screens are built off screen, and calls {@code SearchIndex.search(query, limit)}
 * (also {@code searchAsTyped}, {@code searchAny}, {@code correctionsFor}) and {@code Suggestions.didYouMean/related}.
 *
 * <p>Classes:
 * <ul>
 * <li>{@code SearchIndex} - all entries and the search over them; returns {@code Hit}s ranked by {@code MatchKind}
 * and explained by {@code Reason}.</li>
 * <li>{@code QueryPlan} -> {@code QueryTerm}: a query cut into words (a known two-word phrase is also one term);
 * {@code Token}, {@code Text}, {@code Stemmer}, {@code EditDistance}, {@code KeyboardLayout} - the word tools.</li>
 * <li>{@code SynonymTable} (the data: groups of "core" and "related" words) -> {@code Synonyms} (lookups by stem);
 * {@code Suggestions} - "did you mean" and related words.</li>
 * </ul>
 *
 * <p>Settings: none.
 *
 * <p>Gotchas: no {@code android.*} import may appear here; the classes are covered by JUnit tests in
 * {@code src/test/.../settings/search}. To teach the search a new word for a setting, edit {@code SynonymTable} or add
 * keywords to the row ({@code SearchEntry.addKeywords}) instead of changing the engine. This is not the Arsound track
 * search, which is the {@code search} package.
 */
package app.revanced.extension.soundcloud.settings.search;
