package app.arsound.patches.soundcloud.misc.branding

/**
 * SoundCloud's own launcher icons, offered in the icon picker after the Arsound ones: the name of the original
 * adaptive icon in mipmap-anydpi and the id of the picker variant. "ic_launcher_default" is left out: it is the same
 * picture as "ic_launcher". Must match SoundCloudIconList in the extension.
 *
 * The pictures are not stored in this project: the patch copies them from the APK being patched, before the
 * branding replaces them with the Arsound icon.
 */
internal val SOUNDCLOUD_APP_ICONS = listOf(
    "ic_launcher" to "sc_orange",
    "ic_launcher_og" to "sc_og",
    "ic_launcher_black" to "sc_black",
    "ic_launcher_blue" to "sc_blue",
    "ic_launcher_lavender" to "sc_lavender",
    "ic_launcher_lime" to "sc_lime",
    "ic_launcher_sunset" to "sc_sunset",
    "ic_launcher_soft_purple" to "sc_soft_purple",
    "ic_launcher_hot_pink" to "sc_hot_pink",
    "ic_launcher_silver" to "sc_silver",
    "ic_launcher_rose_gold" to "sc_rose_gold",
    "ic_launcher_chrome" to "sc_chrome",
    "ic_launcher_tie_dye" to "sc_tie_dye",
    "ic_launcher_leopard" to "sc_leopard",
)

/** Prefix of SoundCloud's launcher foregrounds, which the branding replaces with the letter. */
internal const val SOUNDCLOUD_FOREGROUND = "ic_launcher_foreground"

/** The copies of those foregrounds that SoundCloud's icons in the picker draw. */
internal const val SOUNDCLOUD_FOREGROUND_COPY = "arsound_sc_launcher_foreground"
