/**
 * Library screen: hides the playlist import banner.
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
 * <p>Gotchas: the method name is fixed by the patch. This package is only that one class; the "Imported" playlist and
 * the other local-music features live in {@code local}.
 */
package app.revanced.extension.soundcloud.library;
