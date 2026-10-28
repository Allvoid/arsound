package app.arsound.patches.soundcloud.misc.theme

import app.arsound.patches.soundcloud.misc.resources.patchResourceStream
import app.revanced.patcher.patch.ResourcePatchContext
import java.io.File

private const val THEME_RESOURCES = "soundcloud/theme"

/** Parts every theme has, but that apply only once switched on in Settings → Arsound → Appearance (ArsoundTheme). */
private val OPTIONAL_PARTS = listOf("playerSkin")

/** A file shipped with the theme patch (under `src/main/resources/soundcloud/theme`). */
internal fun themeResource(path: String) =
    patchResourceStream("$THEME_RESOURCES/$path", "Missing theme resource $path")

/** The folder the app reads its theme files from (`assets/arsound`). */
internal fun ResourcePatchContext.arsoundAssets(): File = get("assets").resolve("arsound").apply { mkdirs() }

/** The files of the themes (description, fonts) as app assets. */
internal fun ResourcePatchContext.writeThemeAssets(json: ByteArray) {
    val assets = arsoundAssets()
    assets.resolve("themes.json").writeBytes(json)
    val fonts = assets.resolve("fonts").apply { mkdirs() }
    val fontNames = Regex("\"fonts\":\\s*\\{([^}]*)\\}").findAll(String(json))
        .flatMap { block -> Regex("\"(\\w+_\\d{3})\"").findAll(block.groupValues[1]) }
        .map { it.groupValues[1] }.toSet()
    fontNames.forEach { name -> themeResource("fonts/$name.ttf").use { fonts.resolve("$name.ttf").writeBytes(it.readBytes()) } }
}

/**
 * Whole resources a theme brings (drawables, colour lists, layouts): the files of the parts it lists
 * (parts/<part>/<type>/<name>.xml, shared by themes and filled with each theme's tokens) and its own
 * files (overrides/<theme>/<type>/<name>.xml) go to res/<type>/arsound_<theme>__<name>.xml. The app points
 * SoundCloud's resource of that name at them; names starting with arsound_ are additions used by other files.
 *
 * Also writes assets/arsound/theme-resources.json, which tells the app which resources each theme brings, which are
 * optional and which colours it has to fill in for the runtime themes.
 */
internal fun ResourcePatchContext.writeThemeResources(themes: List<ThemeDefinition>) {
    val index = themeResource("index.txt").use { String(it.readBytes()) }.lines().filter { it.endsWith(".xml") }
    val renderer = ThemeRenderer()
    val replaced = StringBuilder("{")
    // The resources of the parts the user switches on in the settings: the app leaves them out while off.
    val optional = OPTIONAL_PARTS.associateWith { part ->
        index.filter { it.startsWith("parts/$part/") }.map { path ->
            val (type, file) = path.split("/").takeLast(2)
            "\"$type/${file.removeSuffix(".xml")}\""
        }.filter { !it.contains("/arsound_") }
    }
    replaced.append("\n  \"_optional\": {")
        .append(optional.entries.joinToString(", ") { (part, names) -> "\"$part\": [${names.joinToString(", ")}]" })
        .append("}")
    for (theme in themes) {
        // The theme's own files come last, so they win over a part's file of the same name.
        val files = index.filter { path -> theme.parts.any { path.startsWith("parts/$it/") } } +
            index.filter { path -> path.startsWith("overrides/${theme.id}/") }
        for (part in theme.parts) {
            if (files.none { it.startsWith("parts/$part/") }) error("Theme ${theme.id} uses an unknown part \"$part\"")
        }
        val names = files.map { path ->
            val (type, file) = path.split("/").takeLast(2)
            val name = file.removeSuffix(".xml")
            val text = themeResource(path).use { String(it.readBytes()) }
            fun write(folder: String, tokens: Map<String, String>) =
                get("res").resolve(folder).apply { mkdirs() }.resolve("arsound_${theme.id}__$name.xml")
                    .writeText(renderer.render(text, theme, tokens, path))
            // A theme with a light look gets each coloured file twice: light in res/<type>, dark in
            // res/<type>-night, and Android picks the one of the phone's mode. A file marked arsound:one-look
            // (the player, dark in every mode) keeps the dark colours. A runtime theme needs one file: its
            // colour resources have the day and the night value (see ThemeRenderer).
            val coloured = Regex("\\$\\{(?!theme\\})\\w+").containsMatchIn(text)
            if (!theme.kind.isRuntime && theme.tokensLight.isNotEmpty() && coloured && !text.contains("arsound:one-look")) {
                write(type, theme.tokensLight)
                write("$type-night", theme.tokens)
            } else {
                write(type, theme.tokens)
            }
            "\"$type/$name\""
        }
        if (replaced.length > 1) replaced.append(",")
        replaced.append("\n  \"${theme.id}\": [${names.distinct().joinToString(", ")}]")
    }
    writeRuntimeColors(themes, renderer, replaced)
    arsoundAssets().resolve("theme-resources.json").writeText(replaced.append("\n}\n").toString())
}

/**
 * The colours of the runtime themes: [resource, token, opacity in percent or -1, dark in both modes], added to
 * theme-resources.json. The app fills them in for the chosen theme; the values written to res here are the seed theme's,
 * for the case it cannot.
 */
private fun ResourcePatchContext.writeRuntimeColors(
    themes: List<ThemeDefinition>,
    renderer: ThemeRenderer,
    replaced: StringBuilder,
) {
    val runtimeColors = renderer.runtimeColors
    if (runtimeColors.isEmpty()) return
    replaced.append(",\n  \"_runtimeColors\": [")
        .append(runtimeColors.values.joinToString(", ") { "[\"${it.name}\", \"${it.token}\", ${it.alpha ?: -1}, ${it.dark}]" })
        .append("]")
    val seed = themes.first { it.kind.isRuntime }
    for ((folder, tokens) in listOf("values" to seed.tokensLight.ifEmpty { seed.tokens }, "values-night" to seed.tokens)) {
        val colors = runtimeColors.values.joinToString("\n") {
            "    <color name=\"${it.name}\">${tokenColor(if (it.dark) seed.tokens else tokens, it.token, it.alpha, it.name)}</color>"
        }
        get("res").resolve(folder).apply { mkdirs() }.resolve("arsound_runtime_colors.xml")
            .writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$colors\n</resources>\n")
    }
}
