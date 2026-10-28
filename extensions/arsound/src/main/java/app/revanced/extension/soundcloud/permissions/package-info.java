/**
 * First-launch greeting, permission explanations and the language switch (the name "permissions" is historical).
 *
 * <p>User view: on the first launch the main screen shows a greeting: what Arsound is, where its settings are, and
 * every permission with what it is for and what stops working without it (everything can be refused). The greeting
 * starts in English; the "EN | RU" switch on it changes the language of the greeting at once and of the whole app after
 * it closes.
 *
 * <p>Entry point: {@code WelcomePermissions.onActivityResumed(Activity)}. No Kotlin patch calls it; it is the
 * "permissions" screen hook of {@code update.UpdateChecker} (see {@code update.ScreenHooks}). It also reveals the app
 * again after a language change ({@code LanguageChange.revealIfPending}).
 *
 * <p>Main classes:
 * <ul>
 * <li>{@code WelcomePermissions} - the flow: when the greeting shows, what its buttons do. Public helpers
 * {@code createHeader} and {@code createDialogContent} give other Arsound dialogs the same logo-and-paragraphs look
 * ({@code search.SignInOffer} and {@code shared.HelpBadge} use them).</li>
 * <li>{@code WelcomeItem} - the list of permissions to explain and whether each is granted.</li>
 * <li>{@code WelcomeContent}, {@code WelcomeViews}, {@code LanguageSwitch} - the views.</li>
 * <li>{@code WelcomeLanguage} - the language while the greeting is on screen; {@code LanguageChange} - applies the
 * language to the app with a fade.</li>
 * </ul>
 *
 * <p>Settings: the greeting is remembered in its own preferences file {@code arsound_welcome} (key {@code shown_v2};
 * raising the suffix shows the greeting once more to everybody).
 *
 * <p>Gotchas: {@code isShownThisLaunch()} is how other prompts ({@code download.MusicAccess},
 * {@code player.VivoHintScheduler}) wait for the next launch; keep it true while the greeting is up.
 */
package app.revanced.extension.soundcloud.permissions;
