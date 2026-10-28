package app.arsound.patches.soundcloud.misc.theme

/** A colour resource of the start screen: its name and the value, #rrggbb or a framework reference. */
internal class SplashColor(val name: String, val value: String)

/** The letter animation (white frames, see the branding patch) tinted with the colour resource [tint]. */
internal class SplashAnimation(val name: String, val tint: String)

/** A style the app can hand to SplashScreen.setSplashScreenTheme: the animation and the background it shows. */
internal class SplashStyle(val name: String, val animation: String, val background: String)

/**
 * What a theme's start screen is made of. Android draws the start screen from the APK's resources before the app
 * runs, so the app can only choose between styles baked here; each kind of look says which resources it needs and the
 * theme patch writes them without asking what kind it is.
 */
internal sealed interface SplashLook {
    val id: String
    val colors: List<SplashColor>
    val animations: List<SplashAnimation>
    val styles: List<SplashStyle>

    /** Files the app reads about this look, by their name in assets/arsound. */
    val assets: Map<String, String> get() = emptyMap()

    /** A shipped theme: one letter colour on one background, the theme's dark look. */
    class Fixed(override val id: String, surface: String, accent: String) : SplashLook {
        override val colors = listOf(
            SplashColor("arsound_theme_${id}_surface", surface),
            SplashColor("arsound_theme_${id}_accent", accent),
        )
        override val animations = listOf(SplashAnimation("arsound_splash_$id", "arsound_theme_${id}_accent"))
        override val styles = listOf(SplashStyle("Arsound.Splash.$id", "arsound_splash_$id", "arsound_theme_${id}_surface"))
    }

    /**
     * Monet: the colours are framework references, which the system resolves from the wallpaper's palette when it
     * shows the start screen, so one style follows the wallpaper by itself.
     */
    class SystemPalette(id: String, surface: String, accent: String) : SplashLook by Fixed(id, surface, accent)

    /**
     * The custom theme: its colours are only known on the phone, so a grid of backgrounds and letter colours is baked
     * (one style for each pair) and the app picks the pair nearest to the user's colours. The seed colours of the
     * theme give a plain style of its own for the case the app cannot choose.
     */
    class Grid(
        override val id: String,
        seedSurface: String,
        seedAccent: String,
        val backgrounds: List<String>,
        val inks: List<String>,
    ) : SplashLook {
        private val seed = Fixed(id, seedSurface, seedAccent)

        /** printf pattern of a cell's style name, with the background's and the letter colour's index (two digits). */
        private val cellStyle = "Arsound.Splash.${id}_%02d_%02d"

        init {
            if (backgrounds.isEmpty() || inks.isEmpty()) error("The start screen grid of theme $id is empty")
            if (backgrounds.toSet().size != backgrounds.size || inks.toSet().size != inks.size) {
                error("The start screen grid of theme $id has a colour twice")
            }
        }

        override val colors = seed.colors +
            backgrounds.mapIndexed { index, color -> SplashColor("arsound_splash_bg_${number(index)}", color) } +
            inks.mapIndexed { index, color -> SplashColor("arsound_splash_ink_${number(index)}", color) }

        override val animations = seed.animations +
            inks.indices.map { SplashAnimation("arsound_splash_a${number(it)}", "arsound_splash_ink_${number(it)}") }

        override val styles = seed.styles + backgrounds.indices.flatMap { background ->
            inks.indices.map { ink ->
                SplashStyle(
                    cellStyle.format(background, ink),
                    "arsound_splash_a${number(ink)}",
                    "arsound_splash_bg_${number(background)}",
                )
            }
        }

        /** The grid for the app: the one list of its colours (the app reads it, nothing repeats it) and the cell pattern. */
        override val assets = mapOf(
            "splash-grid.json" to "{\"theme\":\"$id\",\"cell\":\"$cellStyle\"," +
                "\"backgrounds\":${backgrounds.json()},\"inks\":${inks.json()}}\n",
        )

        private fun number(index: Int) = "%02d".format(index)

        private fun List<String>.json() = joinToString(",", "[", "]") { "\"$it\"" }
    }
}

/**
 * The colours of the custom theme's grid. The first of each list is the exact default look (SoundCloud's black and
 * orange), so an unedited custom theme looks right; then neutral steps and hues around the colour wheel. The
 * backgrounds are dark: the start screen is dark in both modes, and the dark backgrounds the palette makes are dark.
 */
internal object SplashGridColors {
    private const val HUE_STEP = 30

    val backgrounds: List<String> = listOf("#121212", "#000000", "#0D0D0D", "#1A1A1A", "#242424") +
        hues().flatMap { hue -> listOf(.07, .12).map { hex(hue, it, .45) } }

    val inks: List<String> = listOf("#FF5500", "#FFFFFF", "#B2B2B2") +
        hues().flatMap { hue -> listOf(hex(hue, .62, .85), hex(hue, .78, .55)) }

    private fun hues() = (0 until 360 step HUE_STEP).toList()

    /** #RRGGBB of a hue in degrees with the lightness and saturation of the HLS model. */
    private fun hex(hue: Int, lightness: Double, saturation: Double): String {
        val chroma = saturation * minOf(lightness, 1 - lightness)
        val high = lightness + chroma
        val low = lightness - chroma
        fun channel(offset: Int): Int {
            val h = ((hue + offset) % 360) / 60.0
            val value = when {
                h < 1 -> low + (high - low) * h
                h < 3 -> high
                h < 4 -> low + (high - low) * (4 - h)
                else -> low
            }
            return Math.round(value * 255).toInt()
        }
        return "#%02X%02X%02X".format(channel(120), channel(0), channel(240))
    }
}
