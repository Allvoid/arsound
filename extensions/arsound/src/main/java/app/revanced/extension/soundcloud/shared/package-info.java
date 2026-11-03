/**
 * Small helpers that several features share. Look here before writing a new utility; do not copy these into a feature.
 *
 * <p>User view: none directly. These classes give the whole mod the same language switching, dialog look, reflection and
 * file handling.
 *
 * <p>Entry points: none from Kotlin patches. (The mod-wide {@code Utils}, {@code Logger} and {@code PlaybackLog} are not
 * here but in the separate module {@code extensions/arsound-shared}, package {@code app.revanced.extension.shared}.)
 *
 * <p>Classes by purpose:
 * <ul>
 * <li>Text and units: {@code Lang.text(russian, english)} and {@code Lang.russian()} (the app language, read each
 * time); {@code Dimens} (dp to pixels with three roundings); {@code Colors} (contrast, alpha, blending; pure Java, no
 * {@code android.graphics.Color}).</li>
 * <li>Finding SoundCloud's obfuscated parts: {@code Reflect} (a field or method by type and shape, never by name),
 * {@code Rx} (reflective access to SoundCloud's RxJava 3 copy), {@code CallSlot} (carries a value from one hook to
 * another on the same thread), {@code Screens.isMainActivity}, {@code PlaybackStreams.isFileStream}, {@code Urns} (urn
 * strings; the reflection version is {@code local.Urns}).</li>
 * <li>Resources and files: {@code AppResources.identifier} (a resource by name, with the fallback package
 * {@code com.soundcloud.android}), {@code AtomicFiles} (write through a temporary file), {@code StrictJson} (the one
 * strict JSON reader and quoting, for the settings file and the answers of the picture services; pure Java, because
 * {@code org.json} is only a stub in the JVM tests).</li>
 * <li>UI: {@code ArsoundDialog} (a dialog in the look of the chosen theme), {@code HelpBadge} (the small "?" that opens
 * a sheet), {@code Toasts}, {@code AboveBottomBar} (keeps a small element above the player or tab bar),
 * {@code ReorderColumn} (a column of rows rearranged by dragging), {@code ClearBackground} (a see-through fill instead
 * of a null background, which SoundCloud's toolbar behaviour cannot take).</li>
 * </ul>
 *
 * <p>Settings: none.
 *
 * <p>Gotchas: the non-UI helpers must stay free of feature packages (only the UI ones look outward:
 * {@code ArsoundDialog} to {@code theme}, {@code HelpBadge} to {@code permissions}, {@code AboveBottomBar} to
 * {@code player.PlayerSheet}); the pure classes {@code Colors}, {@code Lang}, {@code Reflect}, {@code AtomicFiles},
 * {@code StrictJson} and {@code Urns} have JUnit tests. SoundCloud's classes are renamed on every
 * release, so {@code Reflect} and {@code Rx} find them by shape; fix breakage there once, not in each caller.
 */
package app.revanced.extension.soundcloud.shared;
