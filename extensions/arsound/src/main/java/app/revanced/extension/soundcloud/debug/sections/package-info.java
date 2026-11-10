/**
 * The parts of the file made by "Save the logs", one class per part.
 *
 * <p>User view: none directly; these are the blocks the saved text file consists of, in this order: header, sign-in
 * environment, widget state, crashes, the widget log, one playback log per day (up to two days), the mod log, the app's
 * own system log.
 *
 * <p>Entry points: none from Kotlin patches. {@code debug.LogExport.sections()} creates the list; each class
 * implements {@code debug.LogSection} and is written by {@code debug.LogComposer}.
 *
 * <p>Classes:
 * <ul>
 * <li>{@code HeaderSection} - Arsound and SoundCloud versions, package name, phone model, Android version, save time.</li>
 * <li>{@code SignInEnvironmentSection} - what decides whether sign-in works: network (VPN, DNS, proxy), clock, the
 * browsers that can show the sign-in page, and the relevant Arsound settings (it reads the network settings, so it
 * must follow {@code settings.Settings}).</li>
 * <li>{@code WidgetStateSection} - the player widget's state at the moment of saving: the phone and what it allows the
 * app in the background, the home screen app, the widgets placed, the latest starts of the process and their reasons.</li>
 * <li>{@code CrashSection} - crashes and how the app's processes ended.</li>
 * <li>{@code WidgetLogSection} - the widget's own log ({@code widget.WidgetLog}): process starts, taps and what came of
 * them. Not counted as a record, because every start writes to it.</li>
 * <li>{@code PlaybackFileSection} - one day of the playback log, including SoundCloud's own warnings and errors.</li>
 * <li>{@code ModLogSection} - the mod's own log file (written by {@code Logger}), secrets masked.</li>
 * <li>{@code LogcatSection} - the system log of this app only (Android shows an app no other lines); some phones refuse
 * the command and then one line says so.</li>
 * </ul>
 *
 * <p>Settings: none of its own.
 *
 * <p>Gotchas: a section that cannot be read must not throw past {@code LogComposer}; it leaves one line. Nothing here
 * changes app behaviour. Text that may carry tokens must go through {@code debug.SecretRedactor}.
 */
package app.revanced.extension.soundcloud.debug.sections;
