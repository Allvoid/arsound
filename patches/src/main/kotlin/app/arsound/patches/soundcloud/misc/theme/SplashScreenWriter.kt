package app.arsound.patches.soundcloud.misc.theme

import app.arsound.util.asSequence
import app.revanced.patcher.patch.ResourcePatchContext
import org.w3c.dom.Element

/** The window of the launcher activity after the start screen; the app sets its colour (ArsoundTheme). */
private const val LAUNCH_BACKGROUND = "arsound_launch_background"

/**
 * A start screen per theme: the А9 mark drawing itself on the theme's background (see [SplashLook]).
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

/**
 * The animation of each look. The mark is drawn in its own two colours, the letter line white and the accent line
 * azure, on every theme's dark start screen (the branding patch's animated vector), so each look's animation is a copy.
 * The names stay per look: the styles and the app refer to them.
 */
private fun ResourcePatchContext.writeSplashAnimations(looks: List<SplashLook>) {
    val splash = get("res").resolve("drawable/arsound_splash.xml").readText()
    for (animation in looks.flatMap { it.animations }) {
        get("res").resolve("drawable/${animation.name}.xml").writeText(splash)
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
