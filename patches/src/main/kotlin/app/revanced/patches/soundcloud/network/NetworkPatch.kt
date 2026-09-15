package app.revanced.patches.soundcloud.network

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.soundcloud.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/network/NetworkPatch;"

/**
 * OkHttp is not obfuscated, and every client of the app, including its own copies made with
 * newBuilder(), is created by this method.
 */
private val BytecodePatchContext.okHttpBuildMethod by gettingFirstMethodDeclaratively {
    name("build")
    definingClass("Lokhttp3/OkHttpClient\$Builder;")
}

@Suppress("unused")
val networkPatch = bytecodePatch(
    name = "Network",
    description = "Adds network options to the app HTTP clients, including a developer option that simulates a slow connection.",
) {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        okHttpBuildMethod.addInstructions(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->onBuild(Ljava/lang/Object;)V",
        )
    }
}
