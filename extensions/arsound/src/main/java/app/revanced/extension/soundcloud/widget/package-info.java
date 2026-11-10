/**
 * Home screen widgets: the player widget heart, the "Your likes" widget pictures and the square 2 x 2 player
 * widget.
 *
 * <p>User view: the heart of SoundCloud's player widget takes the colour of the playing cover (or the accent of the
 * chosen Arsound theme when the cover has no colour), fitted to the day or night look of the phone; the "Your likes"
 * widget shows the real cover or avatar (downloaded when SoundCloud's cache has none, never an error page) and a plain
 * grey square when a picture cannot be decoded. "Arsound 2x2" in the launcher's list is a square player: the cover over
 * the whole square, the title, the artist and three buttons on its bottom, the heart in the corner.
 *
 * <p>Entry points (the Kotlin patches are {@code widget/PlayerWidgetHeartPatch.kt} and
 * {@code widget/LikesWidgetPatch.kt}, {@code widget/SquarePlayerWidgetPatch.kt}):
 * <ul>
 * <li>{@code WidgetHeart.begin(Object builder, Context)} - start of the method that builds the widget; picks the
 * colour while the builder still holds the cover.</li>
 * <li>{@code WidgetHeart.tint(RemoteViews)} - just before that method returns; sets a colour filter on the heart (a
 * transparent one when the heart must look as SoundCloud draws it, because the launcher keeps the last filter).</li>
 * <li>{@code WidgetHeart.watchLook(Application)} - called by {@code theme.ThemeLifecycle} at start, rebuilds the widget
 * when the phone flips between day and night.</li>
 * <li>{@code WidgetImages.find(Object finder, Context, String url)} and {@code decode(String path)} - find the cover
 * file of the HTTP cache (downloading it with SoundCloud's own OkHttp when needed) and decode it with a check,
 * instead of SoundCloud's unchecked {@code BitmapFactory.decodeFile}.</li>
 * <li>{@code SquarePlayerWidget.onPlayerWidget(Object presenter, RemoteViews)} - start of
 * {@code PlayerWidgetPresenter.b(RemoteViews)}; when a square is on the home screen, runs SoundCloud's builder once
 * more with a proxy of its resource provider that gives the square's layout and icons.</li>
 * <li>{@code WidgetDiagnostics.onButtonsWired}, {@code onTap}, {@code onProviderReceive} - log lines about the player
 * widget (buttons built, a tap that arrived and what the player did with it, what the phone allows in the
 * background); the Kotlin patch is {@code widget/WidgetDiagnosticsPatch.kt}. {@code onTap} gets the receiver itself,
 * to read the player's state after the tap.</li>
 * <li>{@code WidgetDiagnostics.onApplicationStart(Application)} - called by {@code theme.ArsoundTheme.onApplicationCreate}
 * at every process start: why the process started (Android 15 start history) and what the widgets look like.</li>
 * </ul>
 *
 * <p>Other classes: {@code CoverColor} (hue of a cover), {@code HeartColor} (fits it to the card),
 * {@code WidgetLook} (day/night, forced refresh), {@code CoverFiles} and {@code CoverDownloader} (cover files and
 * refusals), {@code WidgetLog} (the widget's own log file, kept whatever the log settings are; its text goes into
 * the saved logs through {@code debug.sections.WidgetLogSection}, and {@code WidgetStateSection} writes the state
 * of the widgets at the moment of saving).
 *
 * <p>Settings: {@code widget_heart_cover} ({@code WidgetHeart.COVER_COLOR_KEY}, default on), read with
 * {@code Settings.getBoolean}; the switch is on the appearance screen ({@code settings.AppearanceScreen}).
 *
 * <p>Gotchas: the patched methods are named in the Kotlin patches, do not rename them. {@code CoverDownloader} reaches
 * SoundCloud's OkHttp by reflection, because the library is not among the stubs the extension is compiled against.
 * The square's layout must keep the ids of SoundCloud's widget ({@code widget_body}, {@code icon}, {@code title_txt},
 * {@code user_txt}, {@code prev}, {@code toggle_playback}, {@code next}, {@code btn_like}): the builder sets them.
 */
package app.revanced.extension.soundcloud.widget;
