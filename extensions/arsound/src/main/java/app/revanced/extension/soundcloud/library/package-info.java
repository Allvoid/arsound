/**
 * Library screens: hides the playlist import banner, keeps "Your likes" smooth while it scrolls.
 *
 * <p>User view: the "Transfer your gems" banner at the top of the library, which comes back by itself after it is
 * closed, is not shown (default on).
 *
 * <p>Entry point (called by {@code library/HideImportBannerPatch.kt}):
 * {@code HideImportBannerPatch.filterImportBannerState(Object)}. It gets the state SoundCloud picked for the banner (an
 * enum) and returns its {@code DISABLED} value while the setting is on, so the library never builds an item for it.
 * The enum constant is found by name through reflection; any failure returns the original state.
 *
 * <p>Settings: {@code hide_import_banner} ({@code Settings.isHideImportBannerEnabled()}, default on).
 *
 * <p>Smooth scrolling of "Your likes" ({@code LikesListScroll}, patch {@code library/LikesListScrollPatch.kt}, always on):
 * SoundCloud's likes screen reads {@code derivedStateOf { firstVisibleItemIndex }} while it is composed, only to tell
 * whether the list is at its top, and makes the content of its rows anew each time. Every new first row composed the
 * screen and all visible rows again (each row's view state, with a call into the system for the network state), every
 * few frames of a scroll. {@code LikesListScroll.atTopOnly} wraps that calculation so it gives 0 at the top and 1 below
 * it ({@code ListTop}, JUnit {@code ListTopTest}): the screen sees the same answer and is composed again only when the
 * list reaches or leaves its top.
 *
 * <p>Gotchas: the method names are fixed by the patches. The "Imported" playlist and the other local-music features live
 * in {@code local}.
 */
package app.revanced.extension.soundcloud.library;
