package app.arsound.patches.soundcloud.misc.manifest

import app.arsound.util.asSequence
import app.arsound.util.getNode
import app.revanced.patcher.util.Document
import org.w3c.dom.Element

/**
 * Appends an element to the first element named [parentTag] and returns it. The attributes are set in the order given,
 * with their prefix (`android:name`).
 */
internal fun Document.appendElement(parentTag: String, tag: String, vararg attributes: Pair<String, String>): Element {
    val element = createElement(tag)
    attributes.forEach { (name, value) -> element.setAttribute(name, value) }
    getNode(parentTag).appendChild(element)
    return element
}

/** Adds an `activity`, `receiver` or `service` (the [tag]) to the `application` element. */
internal fun Document.addApplicationComponent(tag: String, vararg attributes: Pair<String, String>): Element =
    appendElement("application", tag, *attributes)

/** Whether the `application` element already declares a component named [name] under [tag]. */
internal fun Document.declaresApplicationComponent(tag: String, name: String): Boolean =
    (getNode("application") as Element).getElementsByTagName(tag).asSequence()
        .any { (it as Element).getAttribute("android:name") == name }
