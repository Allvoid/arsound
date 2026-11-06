package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch

/** The Compose modifier that makes its content scroll. Only the Feed and a developer screen use it. */
private val BytecodePatchContext.basicMarqueeMethod by gettingFirstMethodDeclaratively {
    definingClass("Landroidx/compose/foundation/BasicMarqueeKt;")
    name("basicMarquee-1Mj1MLw")
}

/**
 * No scrolling text in the Feed: A line of a Feed post that did not fit scrolled without end, so the tab drew every
 * frame, with or without music. The modifier now gets zero repeats, so it never starts scrolling. It has to stay: it
 * is what keeps such a line on one row, cut at the edge. Without it the line wraps and squeezes its neighbours.
 * Part of the "Arsound" patch, not shown on its own.
 */
val feedMarqueePatch = bytecodePatch {
    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        basicMarqueeMethod.addInstruction(0, "const/4 p1, 0x0")
    }
}
