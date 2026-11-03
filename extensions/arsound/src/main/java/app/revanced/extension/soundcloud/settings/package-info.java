/**
 * The Arsound settings: the stored values and every settings screen. Almost every other package reads its switches here.
 *
 * <p>User view: an "Arsound" row at the top of SoundCloud's own settings opens its own settings activity with a
 * searchable main screen (groups Main, Music, Data, App) and one screen per section; the look follows the SoundCloud
 * resources and the chosen Arsound theme.
 *
 * <p>Entry points (Kotlin: {@code misc/settings/SettingsPatch.kt}, {@code local/ImportEntryPatch.kt}):
 * <ul>
 * <li>{@code SettingsEntry.addEntry(Composer)} - adds the "Arsound" row to SoundCloud's Compose settings list;
 * {@code SettingsEntry.addImportEntry(Composer)} - the rows "Import files from this phone" and "Transfer from Yandex Music"
 * on "Import my music". Both call SoundCloud's own row composable, so the rows look native.</li>
 * <li>{@code YandexImportTile.rows/drawTile} - a "Yandex Music" tile among the apps of SoundCloud's "Import from another
 * app", drawn like SoundCloud's tiles; it opens {@code TransferScreen}.</li>
 * <li>Settings backup: {@code SettingsTransferRows} (the rows and dialogs on "Updates and data"), {@code SettingsTransfer}
 * (the JSON format and the import plan, pure), {@code TransferPlan} (what travels and what never does),
 * {@code TransferFiles} (the Android part).</li>
 * <li>{@code ReVancedSettingsActivity} - declared in the manifest by the patch; only a shell that shows the
 * {@code SettingsScreen} named by the intent.</li>
 * </ul>
 *
 * <p>How it is laid out:
 * <ul>
 * <li>Values: {@code Settings} keeps the preferences file {@code revanced_soundcloud}, the key constants and the
 * {@code isX()} / {@code setX()} methods; {@code BoolSettings} describes each on/off setting once (key and default,
 * through {@code BoolSetting}). A new switch: add the key to {@code Settings}, a {@code BoolSetting} to
 * {@code BoolSettings}, a row to a screen. Some keys live next to their feature instead (equalizer, theme, widget heart,
 * lyrics AI, downloads clean-up), read with {@code Settings.getBoolean/getString/getLong}.</li>
 * <li>Screens: one class per section ({@code AppearanceScreen}, {@code PlaybackScreen}, {@code NetworkScreen},
 * {@code StorageScreen}, {@code AccountScreen}, {@code DeveloperScreen}, ...), listed in {@code SettingsScreens.sections}
 * and grouped by {@code SettingsLayout}. A screen builds rows with {@code SettingsUi} and adds them to the list;
 * {@code SettingsPage} puts consecutive rows into a {@code SettingsGroup} card. Parameters of a feature that is on go in
 * {@code SubRows}. Shared row pieces: {@code ToggleRow}, {@code ActionRow}, {@code SettingRow}.</li>
 * <li>Search: {@code SettingsSearch} (the UI), {@code SearchRecorder} and {@code SettingsSearchIndex} collect every row
 * while the screens are built off screen, so a new row is found without being listed; the engine is in
 * {@code settings.search}.</li>
 * <li>Other: theme and wallpaper widgets ({@code ThemeSheet}, {@code ColorPickerSheet}, {@code HsvColorPicker},
 * {@code WallhavenScreen}), equalizer UI ({@code EqualizerPanel}, {@code EqualizerGraph}), {@code DataReset}
 * ("Reset data" keeps logins, settings and imports), {@code StorageUsage}, {@code TransferScreen} (the transfer from
 * Yandex Music, opened from "Your music"; the work itself is in {@code transfer}).</li>
 * </ul>
 *
 * <p>Gotchas: saved keys must never change; users already have them. A section that is not listed in
 * {@code SettingsLayout} falls into the last group. Texts are written in Russian and English through
 * {@code shared.Lang.text}. Labels other packages quote ({@code region.RegionHintText}) must follow renames here.
 */
package app.revanced.extension.soundcloud.settings;
