/**
 * App icon variants: the list of launcher icons and switching between them.
 *
 * <p>User view: Settings, Arsound, App icon shows the variants (Arsound's own, then SoundCloud's); tapping one changes
 * the launcher icon within a few seconds.
 *
 * <p>Entry points: {@code BrandMark.mark(int)} from {@code BrandMarkPatch.kt}. The branding patch
 * ({@code misc/branding/BrandingPatch.kt}) works on resources and the manifest: it adds one launcher
 * {@code activity-alias} per variant, named {@code com.soundcloud.android.launcher.ArsoundIcon_<id>}, and the pictures
 * {@code arsound_icon_<id>}, {@code arsound_icon_bg_<id>}, {@code arsound_icon_fg_<id>}. This package finds those
 * aliases at run time. The one caller is {@code settings.AppIconScreen}, through {@code AppIcons}.
 *
 * <p>Main classes:
 * <ul>
 * <li>{@code AppIcons} - the public facade: {@code all()}, {@code current(Context)}, {@code apply(Context, id)},
 * {@code drawable(...)}, {@code thumbnail(...)}.</li>
 * <li>{@code IconCatalog} - the rows (id, name, English name, group, English group); the first row is the default.
 * Data lives in {@code AppIconList} (Arsound's) and {@code SoundCloudIconList} (SoundCloud's).</li>
 * <li>{@code LauncherAlias} - enables one alias, then disables the others (the new one first, so the app always has
 * an entry).</li>
 * <li>{@code IconArt} - the pictures of a variant for the picker.</li>
 * <li>{@code BrandMark} - what the app shows outside its own screens: with one of SoundCloud's icons chosen,
 * SoundCloud's cloud as the small icon of the notifications ({@code BrandMark.mark(int)}, called from
 * {@code misc/branding/BrandMarkPatch.kt} wherever SoundCloud takes that icon) and SoundCloud's start screen
 * ({@code theme.SplashStyle} asks {@code BrandMark.isSoundCloud}).</li>
 * </ul>
 *
 * <p>Settings: no key of its own; the chosen icon is the enabled component in the system.
 *
 * <p>Gotchas: the ids and the alias prefix ({@code LauncherAlias.ALIAS_PREFIX}) must match the Kotlin side
 * ({@code misc/branding/AppIcons.kt}, {@code SOUNDCLOUD_APP_ICONS}); an id missing on one side is silently left out.
 * Some launchers drop the home screen shortcut when the icon changes.
 */
package app.revanced.extension.soundcloud.branding;
