package app.arsound.patches.soundcloud.player

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.arsound.patches.soundcloud.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/player/StreamCache;"

/** Size of the stream cache: 120 MB in ad countries, 500 MB elsewhere. */
private val BytecodePatchContext.cacheSizeMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/playback/CountryBasedPlayerCacheSizeProvider;")
    returnType("J")
}

/** Opens the stream cache once: {@code ExoStreamingCache.a()}. */
private val BytecodePatchContext.openCacheMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/exoplayer/ExoStreamingCache;")
    returnType("Landroidx/media3/datasource/cache/SimpleCache;")
}

/** Stream cache: the size and lifetime of cached stream parts are set in the Arsound settings. Part of the "Arsound" patch, not shown on its own. */
val streamCachePatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        cacheSizeMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->hasCustomSize()Z
                move-result v0
                if-eqz v0, :original
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->customSize()J
                move-result-wide v0
                return-wide v0
            """,
            ExternalLabel("original", cacheSizeMethod.getInstruction(0)),
        )
        // Before the monitor: v0 is written before it is read.
        openCacheMethod.addInstructions(
            0,
            """
                iget-object v0, p0, Lcom/soundcloud/android/exoplayer/ExoStreamingCache;->a:Lcom/soundcloud/android/exoplayer/ExoStreamingCacheConfig;
                iget-object v0, v0, Lcom/soundcloud/android/exoplayer/ExoStreamingCacheConfig;->c:Ljava/io/File;
                invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->beforeOpen(Ljava/io/File;)V
            """,
        )
    }
}
