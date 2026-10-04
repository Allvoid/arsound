package app.arsound.patches.soundcloud.misc.theme

import app.arsound.patches.soundcloud.misc.branding.brandingPatch
import app.revanced.patcher.definingClass
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.wideLiteral
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.w3c.dom.Element

private const val THEME_RESOURCES = "soundcloud/theme"

private val BytecodePatchContext.themeApplicationOnCreateMethod by gettingFirstMethodDeclaratively {
    name("onCreate")
    definingClass("Lcom/soundcloud/android/app/RealSoundCloudApplication;")
}

/** The dark veil over the "Your likes" bar on the home screen; two copies of that bar exist in SoundCloud. */
private val BytecodePatchContext.shortcutHeaderStaticMethod by gettingFirstMethodDeclaratively {
    name("<clinit>")
    definingClass("Lcom/soundcloud/android/sdui/components/composables/shortcuts/ShortcutActionHeaderKt;")
}
private val BytecodePatchContext.sectionsShortcutsStaticMethod by gettingFirstMethodDeclaratively {
    name("<clinit>")
    definingClass("Lcom/soundcloud/android/sections/ui/components/ShortcutsKt;")
}

/** SoundCloud's veil colour: 70 % black. */
private const val SHORTCUT_SCRIM = 0xb3000000L

private class ThemeColors(val id: String, val darkSurface: String, val darkAccent: String, val lightSurface: String, val lightAccent: String)

private fun resource(path: String) = object {}.javaClass.classLoader.getResourceAsStream("$THEME_RESOURCES/$path")
    ?: error("Missing theme resource $path")

/** #rrggbb or #rrggbbaa as in themes.json, to Android's #aarrggbb. */
private fun androidColor(hex: String) = if (hex.length == 9) "#" + hex.substring(7) + hex.substring(1, 7) else hex

/** The ids and the surface and accent colours of each theme; the app reads the rest of themes.json itself. */
private fun readThemes(json: String): List<ThemeColors> = json.split("\"id\":").drop(1).map { block ->
    fun color(mode: String, role: String) = Regex("\"$mode\":\\s*\\{[^}]*\"$role\":\\s*\"(#[0-9A-Fa-f]+)\"")
        .find(block)?.groupValues?.get(1) ?: error("No $mode $role in themes.json")
    // A dark-only theme keeps the app dark, so its start screen is dark in both modes too.
    val light = if (Regex("\"darkOnly\":\\s*true").containsMatchIn(block)) "dark" else "light"
    ThemeColors(
        Regex("\"(\\w+)\"").find(block)!!.groupValues[1],
        color("dark", "surface"), color("dark", "special"), color(light, "surface"), color(light, "special"),
    )
}

/** A theme's design tokens (the "tokens" object: name to #rrggbb or #rrggbbaa) and the parts it uses. */
private class ThemeDesign(val id: String, val tokens: Map<String, String>, val parts: List<String>)

private fun readThemeDesigns(json: String): List<ThemeDesign> = json.split("\"id\":").drop(1).map { block ->
    val id = Regex("\"(\\w+)\"").find(block)!!.groupValues[1]
    val tokens = Regex("\"tokens\":\\s*\\{([^}]*)}").find(block)?.groupValues?.get(1).orEmpty()
    val parts = Regex("\"parts\":\\s*\\[([^\\]]*)]").find(block)?.groupValues?.get(1).orEmpty()
    ThemeDesign(
        id,
        Regex("\"(\\w+)\":\\s*\"(#[0-9A-Fa-f]{6,8})\"").findAll(tokens).associate { it.groupValues[1] to it.groupValues[2] },
        Regex("\"(\\w+)\"").findAll(parts).map { it.groupValues[1] }.toList(),
    )
}

/**
 * A part's or a theme's file with the theme filled in: ${theme} is the theme id, ${token} a colour of the theme's
 * tokens as Android's #aarrggbb, ${token@NN} the same colour with NN % opacity.
 */
private fun render(text: String, design: ThemeDesign, file: String): String =
    Regex("\\$\\{(\\w+)(?:@(\\d{1,3}))?}").replace(text) { match ->
        val name = match.groupValues[1]
        if (name == "theme") return@replace design.id
        val hex = design.tokens[name] ?: error("Theme ${design.id} has no token \"$name\" used by $file")
        val color = androidColor(hex).removePrefix("#").let { if (it.length == 6) "FF$it" else it }
        val alpha = match.groupValues[2]
        if (alpha.isEmpty()) "#$color" else "#%02X%s".format(Math.round(alpha.toInt() * 2.55f), color.substring(2))
    }

/**
 * The files of the themes (description, fonts) as app assets, and a start screen per theme: the drawing letter
 * in the theme's accent on the theme's background. Android shows the start screen before the app runs, so it
 * cannot be recoloured at run time; the app picks one of these with SplashScreen.setSplashScreenTheme.
 */
private val themeResourcesPatch = resourcePatch {
    dependsOn(brandingPatch)

    apply {
        val json = resource("themes.json").use { it.readBytes() }
        val assets = get("assets").resolve("arsound").apply { mkdirs() }
        assets.resolve("themes.json").writeBytes(json)
        val fonts = assets.resolve("fonts").apply { mkdirs() }
        val fontNames = Regex("\"fonts\":\\s*\\{([^}]*)}").findAll(String(json))
            .flatMap { block -> Regex("\"(\\w+_\\d{3})\"").findAll(block.groupValues[1]) }
            .map { it.groupValues[1] }.toSet()
        fontNames.forEach { name -> resource("fonts/$name.ttf").use { fonts.resolve("$name.ttf").writeBytes(it.readBytes()) } }

        // Whole resources a theme brings (drawables, colour lists, layouts): the files of the parts it lists
        // (parts/<part>/<type>/<name>.xml, shared by themes and filled with each theme's tokens) and its own
        // files (overrides/<theme>/<type>/<name>.xml) go to res/<type>/arsound_<theme>__<name>.xml. The app points
        // SoundCloud's resource of that name at them; names starting with arsound_ are additions used by other files.
        val index = resource("index.txt").use { String(it.readBytes()) }.lines().filter { it.endsWith(".xml") }
        val replaced = StringBuilder("{")
        for (design in readThemeDesigns(String(json))) {
            // The theme's own files come last, so they win over a part's file of the same name.
            val files = index.filter { path -> design.parts.any { path.startsWith("parts/$it/") } } +
                index.filter { path -> path.startsWith("overrides/${design.id}/") }
            for (part in design.parts) {
                if (files.none { it.startsWith("parts/$part/") }) error("Theme ${design.id} uses an unknown part \"$part\"")
            }
            val names = files.map { path ->
                val (type, file) = path.split("/").takeLast(2)
                val name = file.removeSuffix(".xml")
                val text = resource(path).use { String(it.readBytes()) }
                get("res").resolve(type).apply { mkdirs() }.resolve("arsound_${design.id}__$name.xml")
                    .writeText(render(text, design, path))
                "\"$type/$name\""
            }
            if (replaced.length > 1) replaced.append(",")
            replaced.append("\n  \"${design.id}\": [${names.distinct().joinToString(", ")}]")
        }
        assets.resolve("theme-resources.json").writeText(replaced.append("\n}\n").toString())

        val themes = readThemes(String(json))
        for ((folder, dark) in listOf("values" to false, "values-night" to true)) {
            val colors = themes.joinToString("\n") { theme ->
                val surface = if (dark) theme.darkSurface else theme.lightSurface
                val accent = if (dark) theme.darkAccent else theme.lightAccent
                "    <color name=\"arsound_theme_${theme.id}_surface\">${androidColor(surface)}</color>\n" +
                    "    <color name=\"arsound_theme_${theme.id}_accent\">${androidColor(accent)}</color>"
            }
            get("res").resolve(folder).apply { mkdirs() }.resolve("arsound_theme_colors.xml")
                .writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$colors\n</resources>\n")
        }

        // The drawing letter (white frames from the branding patch) tinted with each theme's accent.
        val splash = get("res").resolve("drawable/arsound_splash.xml").readText()
        val frame = Regex("<item android:drawable=\"@drawable/(arsound_splash_\\d+)\" android:duration=\"(\\d+)\" />")
        for (theme in themes) {
            val tinted = frame.replace(splash) { match ->
                "<item android:duration=\"${match.groupValues[2]}\">" +
                    "<bitmap android:src=\"@drawable/${match.groupValues[1]}\" " +
                    "android:tint=\"@color/arsound_theme_${theme.id}_accent\" /></item>"
            }
            get("res").resolve("drawable/arsound_splash_${theme.id}.xml").writeText(tinted)
        }

        document("res/values/styles.xml").use { document ->
            val resources = document.documentElement
            for (theme in themes) {
                val style = document.createElement("style").apply {
                    setAttribute("name", "Arsound.Splash.${theme.id}")
                    setAttribute("parent", "@style/SoundcloudAppTheme.SplashScreen")
                }
                fun item(name: String, value: String) = style.appendChild(
                    document.createElement("item").apply {
                        setAttribute("name", name)
                        textContent = value
                    },
                ) as Element
                item("windowSplashScreenAnimatedIcon", "@drawable/arsound_splash_${theme.id}")
                item("windowSplashScreenBackground", "@color/arsound_theme_${theme.id}_surface")
                resources.appendChild(style)
            }
        }
    }
}

/** Arsound themes: Applies the colour theme chosen in Settings → Arsound → Appearance. Part of the "Arsound" patch, not shown on its own. */
val themePatch = bytecodePatch {
    dependsOn(themeResourcesPatch)
    compatibleWith("com.soundcloud.android")

    apply {
        themeApplicationOnCreateMethod.addInstruction(
            0,
            "invoke-static { p0 }, Lapp/revanced/extension/soundcloud/theme/ArsoundTheme;" +
                "->onApplicationCreate(Landroid/app/Application;)V",
        )
        // The theme may lighten the veil over the "Your likes" bar, so the bar shows the theme's colours.
        for (method in listOf(shortcutHeaderStaticMethod, sectionsShortcutsStaticMethod)) {
            method.apply {
                val index = indexOfFirstInstructionOrThrow {
                    opcode == Opcode.CONST_WIDE && wideLiteral == SHORTCUT_SCRIM
                }
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register, v${register + 1} }, Lapp/revanced/extension/soundcloud/theme/ArsoundTheme;->shortcutScrim(J)J
                        move-result-wide v$register
                    """,
                )
            }
        }
    }
}
