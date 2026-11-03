/**
 * Wallpaper: a picture behind SoundCloud's main screens, chosen from Wallhaven or Wikimedia Commons.
 *
 * <p>User view: the wallpaper rows of Settings, Arsound, Appearance let the user browse a catalogue in a grid, preview a
 * picture and set it as the app background with a dim and a blur slider. A switch at the top of the catalogue chooses
 * the source: wallhaven.cc (safe pictures need no key; a Wallhaven API key unlocks more) or Wikimedia Commons (free
 * pictures, nothing to register; only the featured and the quality pictures are offered, with the author and the licence
 * shown in the preview). Words typed in Russian are put into English first ({@code SearchWords}). Home, library, search and playlists then show the picture; the player, tab bar, dialogs and sheets keep
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
 * <li>{@code WallpaperSurfaces.backdropPicture(Object, Composer)} and {@code backdropShade(Modifier, Function1, Composer)}
 * - the backdrop of the home screen's content wall (genre filters over a grid of tracks): no tinted picture and no
 * shade; its fill and inner shadow go through {@code screenSurface}, like the fades at the ends of horizontal lists
 * ({@code EdgeShadowKt}), the rows of tabs ({@code AdjustableTabRowKt}) and the loading screen ({@code LoadingKt}).</li>
 * </ul>
 *
 * <p>{@code SearchLayers} keeps the recent searches from showing through the suggestions once the fills are clear.
 *
 * <p>Main classes: {@code WallpaperStore} (the chosen picture, dim, blur, install/remove, the one shared bitmap),
 * {@code WallpaperDrawable}/{@code WallpaperBitmap}/{@code WallpaperBaker} (draw it; the baked copy is cut to the
 * screen and blurred once), {@code WallpaperDownload}, the sources ({@code WallpaperSource} is the interface the screen,
 * the grid, the download and the preview talk to; {@code WallpaperItem} is one picture of any of them;
 * {@code WallpaperSources} lists them and keeps the choice;
 * {@code Wallhaven} is the Wallhaven API client and the first source, its search address is built by
 * {@code WallhavenRequest} and the sorting and the tall-then-wide order are decided by {@code WallhavenRules};
 * {@code CommonsSource} is the second, with {@code CommonsRequest} (the search address), {@code CommonsResponse} (the
 * answer read into pictures with {@code shared.StrictJson}, and files with an unwanted word in their title or
 * categories dropped) and {@code CommonsRules} (filters, order, host check, unwanted words, credit line)), the network calls both share ({@code WallpaperHttp}: the name the services see, redirects checked against the
 * source's hosts, limits on parallel reads; {@code RequestPacer}), the grid
 * ({@code WallhavenGrid}, {@code WallhavenCell}, {@code ThumbnailLoader}, {@code SharpLoader}, {@code GridDiskCache},
 * {@code ShimmerDrawable}), policies ({@code SharpPolicy}, {@code NetworkCost}), and view helpers
 * ({@code ViewKind}, {@code ClassNames}, {@code SurfaceColours}, {@code MainScreen}).
 *
 * <p>Settings ({@code settings.Settings}): {@code arsound_wallpaper_enabled}, {@code arsound_wallpaper_dim},
 * {@code arsound_wallpaper_blur}, {@code arsound_wallpaper_top_bar}, {@code arsound_wallpaper_source} (the catalogue shown),
 * {@code arsound_wallhaven_key}, {@code arsound_wallhaven_adult}, {@code arsound_wallhaven_last} (the last search of either
 * source, with the Commons set). Files: {@code files/arsound/wallpaper/current.jpg} (as chosen) and {@code view.jpg}
 * (baked), kept by "Reset data". Screens: {@code settings.WallpaperRows}, {@code WallhavenScreen},
 * {@code WallpaperPreviewScreen}, {@code WallhavenKeyDialog}.
 *
 * <p>Gotchas: SoundCloud's views are recognised by class name and by colour ({@code ClassNames},
 * {@code SurfaceColours}) because the extension has no compile dependency on them; a SoundCloud update can change
 * what counts as a "base layer". Pure arithmetic ({@code BoxBlur}, {@code CoverCrop}, {@code WallhavenRules},
 * {@code WallhavenRequest}, {@code CommonsRules}, {@code CommonsRequest}, {@code CommonsResponse},
 * {@code WallpaperKeep}) must stay Android-free for the JVM tests. Thumbnails use a bounded cache and skip full
 * downloads on a metered network. Nothing is fetched from an address that no source owns
 * ({@code WallpaperSource#ownsHost}); Commons pictures come only from upload.wikimedia.org and thumb.wikimedia.org over
 * https, two at a time.
 */
package app.revanced.extension.soundcloud.wallpaper;
