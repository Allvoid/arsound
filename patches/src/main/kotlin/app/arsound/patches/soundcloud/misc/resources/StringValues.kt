package app.arsound.patches.soundcloud.misc.resources

import app.arsound.util.asSequence
import app.revanced.patcher.patch.ResourcePatchContext
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Makes sure the resource file at [path] (for example `res/values-ru/strings.xml`) exists, with an empty `resources`
 * root when it has to be created. The file may be missing when SoundCloud has no translation to that language.
 */
internal fun ResourcePatchContext.ensureValuesFile(path: String) {
    val file = get(path)
    if (!file.exists()) {
        file.parentFile.mkdirs()
        file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
    }
}

/**
 * Opens a file shipped with the patches (under `src/main/resources`) as a stream. When it is not there, the patch
 * fails with [missing].
 */
internal fun patchResourceStream(path: String, missing: String) =
    object {}.javaClass.classLoader.getResourceAsStream(path) ?: error(missing)

private fun Document.stringElements() = getElementsByTagName("string").asSequence().map { it as Element }

/** The `<string>` named [name] of a `strings.xml`, or null. */
internal fun Document.findStringOrNull(name: String): Element? =
    stringElements().firstOrNull { it.getAttribute("name") == name }

/** The `<string>` named [name] of a `strings.xml`; fails when the file has none. */
internal fun Document.findString(name: String): Element =
    stringElements().first { it.getAttribute("name") == name }
