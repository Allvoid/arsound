/**
 * Update check and the application-wide screen hooks.
 *
 * <p>User view: on every launch Arsound asks the GitHub releases of the project whether a newer version exists and, on
 * the first screen that opens, offers the ready APK (Telegram channel) or the patches (GitHub). Nothing is downloaded
 * or installed by the app.
 *
 * <p>Entry point: {@code UpdateChecker.onApplicationCreate(Application)}. The Kotlin patch
 * {@code misc/settings/SettingsPatch.kt} inserts a call to it at the very start of SoundCloud's
 * {@code Application.onCreate}. It installs {@code debug.CrashLog}, then registers one activity-lifecycle callback.
 * Every launch-time feature of the mod that reacts to a screen is wired in this package, in
 * {@code UpdateChecker.screenHooks()}, through {@code ScreenHooks}.
 *
 * <p>Main classes:
 * <ul>
 * <li>{@code UpdateChecker} - the release check ({@code check}, {@code compareVersions}), the update sheet and the
 * lifecycle callback; {@code resumedActivity()} gives other code the screen that is in front.</li>
 * <li>{@code ScreenHooks} - an ordered list of {@code onActivityResumed}/{@code onActivityPaused} reactions; one
 * failing hook is logged and does not stop the ones after it.</li>
 * </ul>
 *
 * <p>Settings: {@code update_check} ({@code Settings.isUpdateCheckEnabled()}, default on).
 *
 * <p>Gotchas:
 * <ul>
 * <li>The order of hooks in {@code screenHooks()} matters (for example the music access prompt waits for the
 * greeting). To add a feature that reacts to screens, add one line there.</li>
 * <li>{@code UpdateChecker.VERSION} must equal {@code version} in {@code gradle.properties}; update both on a
 * release.</li>
 * <li>The package name is historical: besides the update check it holds the central screen-hook registry.</li>
 * </ul>
 */
package app.revanced.extension.soundcloud.update;
