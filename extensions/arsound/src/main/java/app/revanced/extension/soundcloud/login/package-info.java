/**
 * Sign-in screens: a "Save logs" button.
 *
 * <p>User view: a small pill in the top corner of the sign-in activities. Before sign-in the Arsound settings cannot
 * be reached, and a failed sign-in is exactly when the developer needs the logs, so the pill saves them from there.
 *
 * <p>Entry point: {@code LoginLogsButton.onActivityResumed(Activity)}. No Kotlin patch calls it; it is registered as the
 * "login logs" screen hook in {@code update.UpdateChecker} (see {@code update.ScreenHooks}) and does nothing on
 * screens other than the sign-in. A tap runs {@code debug.LogExport.save(activity, false)}.
 *
 * <p>Settings: none.
 *
 * <p>Gotchas: the pill is added over the whole activity, not into its layout, so it survives SoundCloud's own screen
 * changes. The log file itself is built in {@code debug} and {@code debug.sections}.
 */
package app.revanced.extension.soundcloud.login;
