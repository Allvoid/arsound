package app.arsound.patches.soundcloud.misc.theme

/**
 * The start screen colours of Monet follow the wallpaper: Android reads framework colour references in a style when
 * the screen is shown, so they are the system palette's tones, not fixed colours.
 */
private const val MONET_SURFACE = "@android:color/system_neutral1_900"
private const val MONET_ACCENT = "@android:color/system_accent1_200"

/**
 * How a theme gets its colours, and with them what its start screen is made of. A kind builds the [SplashLook] of
 * its themes, so the writers of the theme resources never ask which kind a theme is.
 */
internal sealed interface ThemeKind {
    /** The start screen of [theme]. */
    fun splashLook(theme: ThemeDefinition): SplashLook

    /** Whether the colours are made on the phone: the theme's tokens are only a seed (see [ThemeDefinition.tokens]). */
    val isRuntime: Boolean

    /** A shipped theme: its colours are in themes.json and are written into the APK. */
    object Static : ThemeKind {
        override val isRuntime = false

        override fun splashLook(theme: ThemeDefinition): SplashLook =
            SplashLook.Fixed(theme.id, theme.darkColor("surface"), theme.darkColor("special"))
    }

    /**
     * A theme whose colours are made on the phone: "monet" (from the wallpaper) or "custom" (the user's own). Its files
     * name colour resources (@color/arsound_rt_*) instead of holding the colours, and the app fills those in. A
     * [source] that is neither is treated as a runtime theme with a plain start screen.
     */
    class Runtime(private val source: String) : ThemeKind {
        override val isRuntime = true

        override fun splashLook(theme: ThemeDefinition): SplashLook = when (source) {
            "monet" -> SplashLook.SystemPalette(theme.id, MONET_SURFACE, MONET_ACCENT)
            "custom" -> SplashLook.Grid(
                theme.id,
                theme.darkColor("surface"),
                theme.darkColor("special"),
                SplashGridColors.backgrounds,
                SplashGridColors.inks,
            )
            else -> Static.splashLook(theme)
        }
    }
}

/**
 * A theme as themes.json describes it, as far as the patch needs it: the id, the kind, the design tokens (the
 * "tokens" object: name to #rrggbb or #rrggbbaa), the tokens of its light look ("tokensLight", empty for a theme that
 * is always dark) and the parts it uses. The app reads the rest of themes.json itself.
 */
internal class ThemeDefinition(
    val id: String,
    val kind: ThemeKind,
    val tokens: Map<String, String>,
    val tokensLight: Map<String, String>,
    val parts: List<String>,
    /** The text of this theme in themes.json, for the colours of the start screen. */
    private val block: String,
) {
    /** A colour of the dark look ("surface", "special", ...) as it is written in themes.json. */
    fun darkColor(role: String): String =
        Regex("\"dark\":\\s*\\{[^}]*\"$role\":\\s*\"(#[0-9A-Fa-f]+)\"").find(block)?.groupValues?.get(1)
            ?: error("No dark $role in themes.json")

    /** The start screen of this theme, see [SplashLook]. */
    fun splashLook(): SplashLook = kind.splashLook(this)
}

/** Reads every theme of themes.json once. The file is read with regular expressions: it has no library for JSON here. */
internal fun readThemes(json: String): List<ThemeDefinition> = json.split("\"id\":").drop(1).map { block ->
    val id = Regex("\"(\\w+)\"").find(block)!!.groupValues[1]
    val parts = Regex("\"parts\":\\s*\\[([^\\]]*)]").find(block)?.groupValues?.get(1).orEmpty()
    ThemeDefinition(
        id,
        runtimeSourceOf(block)?.let { ThemeKind.Runtime(it) } ?: ThemeKind.Static,
        tokenMap(block, "tokens"),
        tokenMap(block, "tokensLight"),
        Regex("\"(\\w+)\"").findAll(parts).map { it.groupValues[1] }.toList(),
        block,
    )
}

private fun runtimeSourceOf(block: String): String? =
    Regex("\"runtime\":\\s*\"(\\w+)\"").find(block)?.groupValues?.get(1)

private fun tokenMap(block: String, key: String): Map<String, String> {
    val body = Regex("\"$key\":\\s*\\{([^}]*)\\}").find(block)?.groupValues?.get(1).orEmpty()
    return Regex("\"(\\w+)\":\\s*\"(#[0-9A-Fa-f]{6,8})\"").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
}
