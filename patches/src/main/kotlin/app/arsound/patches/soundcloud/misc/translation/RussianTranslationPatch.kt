package app.arsound.patches.soundcloud.misc.translation

import app.arsound.util.asSequence
import app.revanced.patcher.patch.resourcePatch
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The strings and plurals SoundCloud translates into other languages, translated into Russian.
 * Kept in resources/soundcloud/translation; merged into res/values-ru next to Arsound's own Russian strings.
 */
private val TRANSLATIONS = mapOf(
    "strings-ru.xml" to "strings.xml",
    "plurals-ru.xml" to "plurals.xml",
)

/** Russian: Translates the SoundCloud interface into Russian and offers Russian in the per-app language list. Part of the "Arsound" patch, not shown on its own. */
val russianTranslationPatch = resourcePatch {
    compatibleWith("com.soundcloud.android")

    apply {
        TRANSLATIONS.forEach { (source, target) ->
            val stream = object {}.javaClass.classLoader.getResourceAsStream("soundcloud/translation/$source")
                ?: error("Missing translation $source")
            val translated = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }

            val path = "res/values-ru/$target"
            val file = get(path)
            if (!file.exists()) {
                file.parentFile.mkdirs()
                file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
            }
            document(path).use { document ->
                val resources = document.documentElement
                // Some strings keep <xliff:g> placeholders.
                if (!resources.hasAttribute("xmlns:xliff")) {
                    resources.setAttribute("xmlns:xliff", "urn:oasis:names:tc:xliff:document:1.2")
                }
                val existing = resources.childNodes.asSequence()
                    .filterIsInstance<Element>().map { it.getAttribute("name") }.toSet()
                translated.documentElement.childNodes.asSequence().filterIsInstance<Element>()
                    .filter { it.getAttribute("name") !in existing }
                    .forEach { resources.appendChild(document.importNode(it, true)) }
            }
        }

        // Russian in the system list of app languages (Settings → Apps → Arsound → Language).
        document("res/xml/locales_config.xml").use { document ->
            val config = document.documentElement
            val listed = config.getElementsByTagName("locale").asSequence()
                .any { (it as Element).getAttribute("android:name") == "ru" }
            if (!listed) {
                config.appendChild(document.createElement("locale").apply { setAttribute("android:name", "ru") })
            }
        }
    }
}
