/**
 * Sign-in screens and SoundCloud accounts: a "Save logs" button, and switching between several SoundCloud accounts.
 *
 * <p>User view: a small "Save logs" pill in the top corner of the sign-in activities (before sign-in the Arsound settings
 * cannot be reached, and a failed sign-in is exactly when the developer needs the logs). The list of SoundCloud
 * accounts ({@code AccountSheet}): a long press on the profile button of the top bar, the "Switch account" row of
 * SoundCloud's settings ({@code settings.SettingsEntry}), Settings → Arsound → Accounts; on the sign-in screen a
 * "My accounts" pill signs back in to a kept account.
 *
 * <p>Entry points: {@code LoginLogsButton.onActivityResumed} and {@code AccountEntry.onActivityResumed}, screen hooks
 * registered in {@code update.UpdateChecker} (see {@code update.ScreenHooks}). {@code AccountSwitch.subscribePurge} is
 * called by {@code misc/account/AccountSwitchPatch} from SoundCloud's authenticator when an account is taken out.
 * {@code SignInAccount.add} stands in for {@code addAccountExplicitly} in SoundCloud's sign-in
 * ({@code misc/account/SignInAccountPatch}): an account Android refuses is retried under another name, and explained.
 *
 * <p>Settings: none. The kept accounts (with their tokens) are in the preferences file {@code arsound_accounts}, outside
 * the settings backup; their pictures in {@code files/arsound_accounts}.
 *
 * <p>Gotchas: a switch must not use SoundCloud's own sign-out, which ends the session on the server ({@code /sign_out})
 * and kills the kept tokens; it takes the account out of the account manager instead and waits until SoundCloud has
 * wiped the old user's data before it puts the next account in and restarts the app. The pills are added over the whole
 * activity, not into its layout.
 */
package app.revanced.extension.soundcloud.login;
