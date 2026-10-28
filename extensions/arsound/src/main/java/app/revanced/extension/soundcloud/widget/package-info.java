/**
 * Home screen widgets: the player widget heart and the "Your likes" widget pictures.
 *
 * <p>User view: the heart of SoundCloud's player widget takes the colour of the playing cover (or the accent of the
 * chosen Arsound theme when the cover has no colour), fitted to the day or night look of the phone; the "Your likes"
 * widget shows the real cover or avatar (downloaded when SoundCloud's cache has none, never an error page) and a plain
 * grey square when a picture cannot be decoded.
 *
 * <p>Entry points (the Kotlin patches are {@code widget/PlayerWidgetHeartPatch.kt} and
 * {@code widget/LikesWidgetPatch.kt}):
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
 * </ul>
 *
 * <p>Other classes: {@code CoverColor} (hue of a cover), {@code HeartColor} (fits it to the card),
 * {@code WidgetLook} (day/night, forced refresh), {@code CoverFiles} and {@code CoverDownloader} (cover files and
 * refusals).
 *
 * <p>Settings: {@code widget_heart_cover} ({@code WidgetHeart.COVER_COLOR_KEY}, default on), read with
 * {@code Settings.getBoolean}; the switch is on the appearance screen ({@code settings.AppearanceScreen}).
 *
 * <p>Gotchas: the patched methods are named in the Kotlin patches, do not rename them. {@code CoverDownloader} reaches
 * SoundCloud's OkHttp by reflection, because the library is not among the stubs the extension is compiled against.
 */
package app.revanced.extension.soundcloud.widget;
