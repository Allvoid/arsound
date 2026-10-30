/**
 * Wallpaper: a picture behind SoundCloud's main screens, chosen from Wallhaven.
 *
 * <p>User view: the wallpaper rows of Settings, Arsound, Appearance let the user browse wallhaven.cc in a grid (safe pictures
 * need no key; a Wallhaven API key unlocks more), preview a picture and set it as the app background with a dim and a
 * blur slider. Home, library, search and playlists then show the picture; the player, tab bar, dialogs and sheets keep
 * their colours.
 *
 * <p>Entry points (Kotlin: {@code misc/theme/WallpaperSurfacesPatch.kt}):
 * <ul>
 * <li>{@code WallpaperSurfaces.onFragmentViewCreated(View root)} - SoundCloud's fragment has made its view; the opaque
 * base fills are cleared ({@code ScreenBackgrounds}) so the picture shows through.</li>
 * <li>{@code WallpaperSurfaces.screenSurface(Composer)} - the base colour of a Compose screen (SoundCloud's
 * {@code Colors.j} in the theme, the scaffold and the collapsing title bar); transparent while a wallpaper is shown.</li>
 * <li>{@code WallpaperSurfaces.headerPicture(String, Composer)} and {@code headerBackdrop(Modifier, Composer)} - the
 * cover blurred behind the title of a profile or a playlist; none (clear) while a wallpaper is shown. The playlist
 * header's fades also take {@code screenSurface}.</li>
 * <li>{@code WallpaperSurfaces.onActivityPreCreated/onActivityResumed} - called by {@code theme.ThemeLifecycle}, not by a
 * patch.</li>
 * <li>{@code WallpaperSurfaces.topBarColour(Composer)} and {@code topBarModifier(Modifier)} - the band of SoundCloud's
 * top bar ({@code TopAppBarKt}) in the style chosen in Appearance ({@code TopBarStyle}); the fade and the glass are
 * brushes laid under the bar ({@code TopBarLook}), the glass from a blurred strip of the wallpaper.</li>
 * <li>{@code WallpaperSurfaces.previewCellFill(Composer)} and {@code trackCellBackground(int)} - no solid fill under a
 * Go+ preview track.</li>
 * </ul>
 *
 * <p>{@code SearchLayers} keeps the recent searches from showing through the suggestions once the fills are clear.
 *
 * <p>Main classes: {@code WallpaperStore} (the chosen picture, dim, blur, install/remove, the one shared bitmap),
 * {@code WallpaperDrawable}/{@code WallpaperBitmap}/{@code WallpaperBaker} (draw it; the baked copy is cut to the
 * screen and blurred once), {@code WallpaperDownload}, {@code Wallhaven} (API client), the grid
 * ({@code WallhavenGrid}, {@code WallhavenCell}, {@code ThumbnailLoader}, {@code SharpLoader}, {@code GridDiskCache},
 * {@code ShimmerDrawable}), policies ({@code SharpPolicy}, {@code NetworkCost}), and view helpers
 * ({@code ViewKind}, {@code ClassNames}, {@code SurfaceColours}, {@code MainScreen}).
 *
 * <p>Settings ({@code settings.Settings}): {@code arsound_wallpaper_enabled}, {@code arsound_wallpaper_dim},
 * {@code arsound_wallpaper_blur}, {@code arsound_wallpaper_top_bar}, {@code arsound_wallhaven_key}, {@code arsound_wallhaven_adult},
 * {@code arsound_wallhaven_last}. Files: {@code files/arsound/wallpaper/current.jpg} (as chosen) and {@code view.jpg}
 * (baked), kept by "Reset data". Screens: {@code settings.WallpaperRows}, {@code WallhavenScreen},
 * {@code WallpaperPreviewScreen}, {@code WallhavenKeyDialog}.
 *
 * <p>Gotchas: SoundCloud's views are recognised by class name and by colour ({@code ClassNames},
 * {@code SurfaceColours}) because the extension has no compile dependency on them; a SoundCloud update can change
 * what counts as a "base layer". Pure arithmetic ({@code BoxBlur}, {@code CoverCrop}, {@code WallhavenRules},
 * {@code WallpaperKeep}) must stay Android-free for the JVM tests. Thumbnails use a bounded cache and skip full
 * downloads on a metered network.
 */
package app.revanced.extension.soundcloud.wallpaper;
