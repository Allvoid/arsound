/**
 * Arsound themes: colours, fonts, corner radii, start screen and animated background laid over SoundCloud's interface
 * at run time.
 *
 * <p>User view: Settings, Arsound, Appearance lets the user pick a theme (or "SoundCloud" for the original look), the
 * Monet theme (Material You colours from the wallpaper) and a Custom theme (own accent and backgrounds). The change
 * applies after a restart of the app, which {@code ArsoundTheme.restartApp} does. Requires Android 11 (API 30) or newer;
 * on older phones nothing is applied.
 *
 * <p>How it works: the theme patch ({@code misc/theme/ThemePatch.kt}) puts {@code assets/arsound/themes.json} and fonts
 * into the APK. At run time {@code ThemeTableBuilder} builds a small resource table ({@code ArscWriter}) that overrides
 * SoundCloud's colour tokens, and {@code ThemeLoader} adds it to the app's resources as one {@code ResourcesLoader}.
 *
 * <p>Entry points (Kotlin: {@code misc/theme/ThemePatch.kt}, {@code LibraryRowIcons.kt}):
 * <ul>
 * <li>{@code ArsoundTheme.onApplicationCreate(Application)} - start of {@code Application.onCreate}; starts
 * {@code ThemeLifecycle}, which applies the theme to the app and to every screen before it is created.</li>
 * <li>{@code ArsoundTheme.libraryRowIcon(View)} / {@code libraryRowIcon(String)} - icon of a Library row;
 * {@code ArsoundTheme.shortcutScrim(long)} - the veil over the "Your likes" bar; {@code HeaderArtwork.alpha(int)} -
 * opacity of the picture behind a page title.</li>
 * <li>{@code HomeGreeting} - a custom view named in the theme's own copy of the home layout (resource XML), not called
 * from Kotlin code.</li>
 * </ul>
 *
 * <p>Main classes: {@code ArsoundTheme} (the facade the rest of the app calls: {@code current}, {@code setCurrent},
 * {@code palette}, {@code isNight}, {@code effect}), {@code ThemeCatalog} (themes.json), {@code RuntimeThemes} +
 * {@code PaletteEngine} + {@code CustomTheme} (themes made on the phone; a Java port of
 * {@code tools/branding/theme_from_design.py}), {@code ThemeLifecycle}, {@code ThemeLoader}, {@code NightMode},
 * {@code SplashStyle}/{@code SplashGrid}/{@code SplashColors} (start screen), {@code ThemeEffect} +
 * {@code ThemeBackdrop} (animated background, scenes in {@code theme.effect}), {@code OriginalAssets}.
 *
 * <p>Settings: {@code arsound_theme} ({@code ArsoundTheme.KEY}, a theme id, default {@code soundcloud}),
 * {@code arsound_player_skin} (new player, beta, off), {@code arsound_custom_theme} (JSON), and in
 * {@code settings.Settings}: {@code theme_effects}, {@code theme_effects_density}, {@code hide_header_artwork}.
 * Screens: {@code settings.AppearanceScreen}, {@code CustomThemeScreen}, {@code ThemeSheet}.
 *
 * <p>Gotchas: a theme is told apart by its {@code Theme.key()}, not only its id, because Monet and Custom colours change
 * while the app runs. The Java palette code must stay in step with {@code tools/branding/theme_from_design.py} and with
 * the Kotlin side ({@code ThemeCatalog.kt}, {@code ThemeRenderer.kt}). Patched method names are fixed. Use
 * {@code wallpaper} for the picture behind the screens.
 */
package app.revanced.extension.soundcloud.theme;
