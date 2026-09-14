package app.revanced.patches.soundcloud.misc.appname

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.patch.stringOption
import app.revanced.util.asSequence
import org.w3c.dom.Element

@Suppress("unused")
val appNamePatch = resourcePatch(
    name = "Custom app name",
    description = "Changes the app name shown on the home screen and in the \"Open with\" dialog.",
) {
    compatibleWith("com.soundcloud.android")

    val appName by stringOption(
        default = "Arsound",
        name = "App name",
        description = "The name of the app.",
        required = true,
    )

    apply {
        // The application, its launcher activity and the "Open with" dialog all use this string.
        // Some translations override it, so every values folder is updated.
        get("res").listFiles { file -> file.isDirectory && file.name.startsWith("values") }!!
            .map { it.resolve("strings.xml") }
            .filter { it.exists() }
            .forEach { stringsFile ->
                document("res/${stringsFile.parentFile.name}/strings.xml").use { document ->
                    document.getElementsByTagName("string").asSequence()
                        .map { it as Element }
                        .firstOrNull { it.getAttribute("name") == "app_name" }
                        ?.textContent = appName!!
                }
            }
    }
}
