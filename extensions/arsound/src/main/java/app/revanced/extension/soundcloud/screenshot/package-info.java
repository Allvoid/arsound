/**
 * Screenshot sharing: controls SoundCloud's "Share screenshot" sheet.
 *
 * <p>User view: on Android 14 and later SoundCloud opens a share sheet after every screenshot. It is off by default
 * and can be turned on in the settings; when on, one screenshot opens it exactly once.
 *
 * <p>Entry points in {@code ScreenshotShare} (called by {@code misc/screenshot/ScreenshotSharePatch.kt}):
 * <ul>
 * <li>{@code register(Object callback)} - a screen asks to watch for screenshots; with the setting off the answer is
 * false, so Android does not even tell the user that the app noticed the screenshot.</li>
 * <li>{@code unregister(Object callback)} - true only for callbacks that were registered here.</li>
 * <li>{@code shouldShare()} - first line of the callback; false for the second callback of the same screenshot
 * (two callbacks within 1.5 s, because the full-screen player keeps the screen below it started).</li>
 * </ul>
 *
 * <p>Settings: {@code screenshot_share} ({@code Settings.isScreenshotShareEnabled()}, default off).
 *
 * <p>Gotchas: turning the setting on takes effect as screens restart. Keep the method names and signatures; the patch
 * code addresses them in smali.
 */
package app.revanced.extension.soundcloud.screenshot;
