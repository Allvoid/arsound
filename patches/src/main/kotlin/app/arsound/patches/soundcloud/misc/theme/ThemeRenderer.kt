package app.arsound.patches.soundcloud.misc.theme

/** The glyph on an accent button. A shipped theme may give it; without one it is white, as SoundCloud draws it. */
private const val ON_ACCENT = "onAccent"
private const val ON_ACCENT_DEFAULT = "#FFFFFF"

/** #rrggbb or #rrggbbaa as in themes.json, to Android's #aarrggbb. */
internal fun androidColor(hex: String) = if (hex.length == 9) "#" + hex.substring(7) + hex.substring(1, 7) else hex

/**
 * A colour of a runtime theme that the app sets when it builds the theme's resource table: the resource the files
 * name, the token it comes from, the opacity in percent (null: the token's own) and whether it keeps the dark look in
 * both modes (the files marked arsound:one-look).
 */
internal class RuntimeColor(val name: String, val token: String, val alpha: Int?, val dark: Boolean)

/** A token's colour as Android's #aarrggbb, with the opacity in percent in place of its own when given. */
internal fun tokenColor(tokens: Map<String, String>, name: String, alpha: Int?, file: String): String {
    val hex = tokens[name] ?: if (name == ON_ACCENT) ON_ACCENT_DEFAULT else error("No token \"$name\" used by $file")
    val color = androidColor(hex).removePrefix("#").let { if (it.length == 6) "FF$it" else it }
    return if (alpha == null) "#$color" else "#%02X%s".format(Math.round(alpha * 2.55f), color.substring(2))
}

/**
 * Fills the theme into the text of a part's or a theme's file: ${theme} is the theme id, ${token} a colour of the
 * theme's tokens as Android's #aarrggbb, ${token@NN} the same colour with NN % opacity.
 *
 * For a runtime theme the colour is not known yet: the file names a colour resource of the app instead
 * (@color/arsound_rt_accent_30), collected in [runtimeColors], and the app puts the real colour into its resource
 * table when it builds the theme. A file marked arsound:one-look names the dark colours, which the other files get in
 * the night folder only; the colour of the glyph on the accent follows the mode like the accent does.
 */
internal class ThemeRenderer {
    /** The runtime colours the rendered files name, by resource name. */
    val runtimeColors = sortedMapOf<String, RuntimeColor>()

    fun render(text: String, theme: ThemeDefinition, tokens: Map<String, String>, file: String): String =
        Regex("\\$\\{(\\w+)(?:@(\\d{1,3}))?\\}").replace(text) { match ->
            val name = match.groupValues[1]
            if (name == "theme") return@replace theme.id
            val alpha = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
            if (!theme.kind.isRuntime) return@replace tokenColor(tokens, name, alpha, "$file of theme ${theme.id}")
            if (name != ON_ACCENT && name !in theme.tokens) {
                error("Theme ${theme.id} has no token \"$name\" used by $file")
            }
            val dark = name != ON_ACCENT && text.contains("arsound:one-look")
            val resource = "arsound_rt_" + (if (dark) "dark_" else "") +
                name.replace(Regex("[A-Z]")) { "_" + it.value.lowercase() } + (alpha?.let { "_$it" } ?: "")
            runtimeColors[resource] = RuntimeColor(resource, name, alpha, dark)
            "@color/$resource"
        }
}
