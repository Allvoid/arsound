package app.arsound.patches.soundcloud.misc.theme

import app.arsound.util.asSequence
import app.revanced.patcher.patch.ResourcePatchContext
import org.w3c.dom.Element

/** The window of the launcher activity after the start screen; the app sets its colour (ArsoundTheme). */
private const val LAUNCH_BACKGROUND = "arsound_launch_background"

/**
 * A start screen per theme: the drawing letter in the theme's accent on the theme's background (see [SplashLook]).
 * Android shows the start screen before the app runs, so it cannot be recoloured at run time; the app picks one of
 * these with SplashScreen.setSplashScreenTheme.
 */
internal fun ResourcePatchContext.writeSplashScreens(themes: List<ThemeDefinition>) {
    val looks = themes.map { it.splashLook() }
    writeSplashColors(looks)
    val assets = arsoundAssets()
    looks.flatMap { it.assets.entries }.forEach { (name, text) -> assets.resolve(name).writeText(text) }
    writeSplashAnimations(looks)
    writeSplashStyles(looks)
}

/**
 * The start screen is dark in both modes: the app it opens is dark whatever the phone's mode, and a light
 * start screen flashed white before it. The window of the launcher activity that follows the start screen is
 * drawn by the app itself, so the app sets its colour (the theme's surface) when it builds the theme.
 */
private fun ResourcePatchContext.writeSplashColors(looks: List<SplashLook>) {
    for (folder in listOf("values", "values-night")) {
        val colors = (looks.flatMap { it.colors } + SplashColor(LAUNCH_BACKGROUND, "#121212")).joinToString("\n") {
            "    <color name=\"${it.name}\">${androidColor(it.value)}</color>"
        }
        get("res").resolve(folder).apply { mkdirs() }.resolve("arsound_theme_colors.xml")
            .writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$colors\n</resources>\n")
    }
}

/** The drawing letter (white frames from the branding patch) tinted with each look's colours. */
private fun ResourcePatchContext.writeSplashAnimations(looks: List<SplashLook>) {
    val splash = get("res").resolve("drawable/arsound_splash.xml").readText()
    val frame = Regex("<item android:drawable=\"@drawable/(arsound_splash_\\d+)\" android:duration=\"(\\d+)\" />")
    for (animation in looks.flatMap { it.animations }) {
        val tinted = frame.replace(splash) { match ->
            "<item android:duration=\"${match.groupValues[2]}\">" +
                "<bitmap android:src=\"@drawable/${match.groupValues[1]}\" " +
                "android:tint=\"@color/${animation.tint}\" /></item>"
        }
        get("res").resolve("drawable/${animation.name}.xml").writeText(tinted)
    }
}

/** The styles the app hands to SplashScreen.setSplashScreenTheme, and the window background after the start screen. */
private fun ResourcePatchContext.writeSplashStyles(looks: List<SplashLook>) {
    document("res/values/styles.xml").use { document ->
        val resources = document.documentElement
        for (splashStyle in looks.flatMap { it.styles }) {
            val style = document.createElement("style").apply {
                setAttribute("name", splashStyle.name)
                setAttribute("parent", "@style/SoundcloudAppTheme.SplashScreen")
            }
            fun item(name: String, value: String) = style.appendChild(
                document.createElement("item").apply {
                    setAttribute("name", name)
                    textContent = value
                },
            ) as Element
            item("windowSplashScreenAnimatedIcon", "@drawable/${splashStyle.animation}")
            item("windowSplashScreenBackground", "@color/${splashStyle.background}")
            resources.appendChild(style)
        }
        // The launcher activity's window, after the start screen, is SoundCloud's black. It is drawn in the app's
        // own process, so the app can give it the theme's exact surface colour.
        resources.getElementsByTagName("style").asSequence().map { it as Element }
            .first { it.getAttribute("name") == "SoundcloudAppTheme.SplashScreen.Legacy" }
            .getElementsByTagName("item").asSequence().map { it as Element }
            .first { it.getAttribute("name") == "android:windowBackground" }
            .textContent = "@color/$LAUNCH_BACKGROUND"
    }
}
