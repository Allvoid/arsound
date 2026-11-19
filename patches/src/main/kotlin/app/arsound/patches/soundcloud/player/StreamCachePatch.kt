package app.arsound.patches.soundcloud.player

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.hooks.GuardExit
import app.arsound.patches.soundcloud.misc.hooks.insertGuard
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

private const val APP_STORAGE = "Lapp/revanced/extension/shared/AppStorage;"
private const val CACHING_MODULE = "Lcom/soundcloud/android/playback/ExoPlayerCachingModule\$Companion;"

/** The folder of the cache SoundCloud plays from (`exocache` in the shared app folder). */
private val BytecodePatchContext.playerCacheFolderMethod by gettingFirstMethodDeclaratively {
    name("d")
    definingClass(CACHING_MODULE)
    returnType("Ljava/io/File;")
}

/** The streaming cache's settings, with its folder. */
private val BytecodePatchContext.streamingCacheConfigMethod by gettingFirstMethodDeclaratively {
    name("f")
    definingClass(CACHING_MODULE)
    returnType("Lcom/soundcloud/android/exoplayer/ExoStreamingCacheConfig;")
}

/** The offline HLS cache's settings, with its folder. */
private val BytecodePatchContext.offlineCacheConfigMethod by gettingFirstMethodDeclaratively {
    name("b")
    definingClass(CACHING_MODULE)
    returnType("Lcom/soundcloud/android/exoplayer/ExoOfflineCacheConfig;")
}

/** Stream cache: the size and lifetime of cached stream parts are set in the Arsound settings. Part of the "Arsound" patch, not shown on its own. */
val streamCachePatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        // A size set by the user replaces the default one; the size is a long, so the guard returns it from a pair.
        cacheSizeMethod.insertGuard(
            "invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->hasCustomSize()Z",
            GuardExit.Custom(
                "move-result",
                """
                    invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->customSize()J
                    move-result-wide v0
                    return-wide v0
                """,
            ),
        )
        // The folders of the caches: the shared app folder only if it can be written. On some phones Android returns
        // its path but cannot make it (bot ticket #65): the cache could not open there and no streamed track started.
        // With null (or the app's own files first) SoundCloud's own fallback, the app's files, is used.
        // Counted per method: a total of 3 could also come from two calls in one method and none in another.
        listOf(playerCacheFolderMethod, streamingCacheConfigMethod, offlineCacheConfigMethod).forEach { method ->
            var replaced = 0
            method.implementation!!.instructions.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    instruction.methodReference?.definingClass == "Landroid/content/Context;" &&
                    instruction.methodReference?.name in setOf("getExternalFilesDir", "getExternalFilesDirs")
            }.forEach { (index, instruction) ->
                val call = instruction as FiveRegisterInstruction
                val name = instruction.methodReference!!.name
                val result = if (name == "getExternalFilesDirs") "[Ljava/io/File;" else "Ljava/io/File;"
                method.replaceInstruction(
                    index,
                    "invoke-static { v${call.registerC}, v${call.registerD} }, " +
                        "$APP_STORAGE->${if (name == "getExternalFilesDirs") "externalFilesDirs" else "externalFilesDir"}" +
                        "(Landroid/content/Context;Ljava/lang/String;)$result",
                )
                replaced++
            }
            if (replaced != 1) throw PatchException("Expected 1 cache folder call in ${method.name}, found $replaced")
        }

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
