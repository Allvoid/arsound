/**
 * The bottom bar of SoundCloud: the user takes out the tabs Home, Feed and Search (Library always stays), and a search
 * button stands in the top bar of the Library while the Search tab is out.
 *
 * <p>User view: Settings, "Нижняя панель": a tick per tab. The app starts on the first tab that stays and goes back to
 * it. A change applies after a restart of the app, which the screen offers.
 *
 * <p>Entry points (Kotlin: {@code navbar/BottomTabsPatch.kt}): {@code BottomTabs.arrange(List)} wraps the list of tabs
 * where {@code NavigationTargetsProvider.a()} returns it and where {@code MainNavigationPresenter.a(...)} gets the list
 * the server updated; {@code BottomTabs.hideButtons(View)} runs at the end of {@code MainNavigationView.setupMenuItems};
 * {@code LibrarySearchButton.onPrepareMenu(Menu)} runs at the start of {@code LibraryFragment.onPrepareOptionsMenu}.
 * {@code HidePaywallPatch.filterNavigationTabs} (upsell) drops the Upgrade tab and then calls {@code arrange} as well.
 *
 * <p>Main classes: {@code BottomTabs} (hooks), {@code TabOrder} (pure ordering, tested), {@code TabKind} (which class
 * makes which tab, and its setting), {@code LibrarySearchButton}.
 *
 * <p>Settings: {@code hide_tab_home}, {@code hide_tab_feed}, {@code hide_tab_search} (default off, written at once);
 * {@code hide_upgrade_tab} stays in the same screen.
 *
 * <p>Gotchas: a hidden tab is not removed from the list: it moves to the end and only its button is hidden. SoundCloud
 * numbers tabs by position everywhere, and a deep link or a shortcut to a hidden tab still works. The Upgrade tab is
 * appended by SoundCloud after the server answers, so it is always kept last: otherwise the numbers of the hidden tabs
 * shift when it arrives and a tap lands on another tab.
 */
package app.revanced.extension.soundcloud.navbar;
