/**
 * Diagnostics: "Save the logs", crash records, the playback timeline and measurements of the player sheet.
 *
 * <p>User view: Settings, Arsound, Developer has "Save the logs"; the sign-in page has the same through a small pill
 * ({@code login} package). It writes one text file to Downloads (header, sign-in environment, crashes, playback log of
 * the last two days, the mod log, the app's system log) and offers to send it. Secrets are masked first.
 *
 * <p>Entry points (Kotlin side: {@code debug/PlaybackTimelinePatch.kt}, {@code debug/PlayerBarLogPatch.kt}):
 * <ul>
 * <li>{@code PlaybackTimeline.plant()} - at the start of the application; plants a Timber tree that catches SoundCloud's
 * playback lines (the release build plants none) and writes them to {@code PlaybackLog} with the delay of every track
 * start and its cause. Does nothing when the playback log setting is off.</li>
 * <li>{@code PlayerBarLog.logBind / logReset / logPlayState} - three hooks in the collapsed player bar that only write
 * log lines, to catch the rare empty bar. They change no behaviour.</li>
 * </ul>
 * Not called by patches but by extension code: {@code CrashLog.install(Application)} (the first line of
 * {@code update.UpdateChecker.onApplicationCreate}), {@code LogExport.save(Activity, boolean)} (the developer screen and
 * {@code login.LoginLogsButton}), {@code SlideJank} (called from {@code player.PlayerSheet}).
 *
 * <p>Main classes:
 * <ul>
 * <li>{@code LogExport} - builds the file: {@code LogComposer} writes the {@code LogSection}s (in
 * {@code debug.sections}) one after another to a {@code LogDestination} (Downloads through the media store on
 * Android 10+, the app's own files folder before).</li>
 * <li>{@code CrashLog} - the stack of every uncaught exception is written to a small file at once; Android's own
 * record of how the last processes ended (Android 11+) is read when the logs are saved.</li>
 * <li>{@code SecretRedactor} and {@code RedactedCopy} - hide tokens, authorization codes and cookies before text
 * leaves the phone.</li>
 * <li>{@code ProcessCapture} - reads a command's output with a time limit (logcat can hang); {@code ReportLines} - safe
 * {@code name: value} lines.</li>
 * <li>{@code SlideJank} - frame timing while the player sheet is dragged, one line per slide in the playback log.</li>
 * </ul>
 *
 * <p>Settings: the playback log switch has the key {@code playback_log} ({@code PlaybackTimeline.ENABLED}, read with
 * {@code Settings.getBoolean}, default on in code, applies at the next start). {@code PlaybackLog} itself and the
 * mod's {@code Logger} are in the separate module {@code extensions/arsound-shared} (package
 * {@code app.revanced.extension.shared}).
 *
 * <p>Gotchas: never put a new secret-carrying field in a section without passing it through {@code SecretRedactor}.
 * A section that fails writes one line and the next sections are still written. The comment of
 * {@code debug/PlaybackTimelinePatch.kt} says "off by default" and the Javadoc of {@code PlaybackTimeline} says
 * "personal build only", but the code default is on; trust {@code PlaybackTimeline.isEnabled()}.
 */
package app.revanced.extension.soundcloud.debug;
