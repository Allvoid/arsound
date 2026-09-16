package app.arsound.patches.soundcloud.misc.extension

import app.revanced.patcher.definingClass
import app.revanced.patcher.name
import app.revanced.patcher.returnType
import app.arsound.patches.shared.misc.extension.extensionHook
import app.arsound.patches.shared.misc.extension.sharedExtensionPatch

/**
 * The tracking API is created during the application startup,
 * before any activity exists, so the context is taken from the application class.
 */
internal val applicationOnCreateHook = extensionHook {
    name("onCreate")
    definingClass("Lcom/soundcloud/android/app/RealSoundCloudApplication;")
    returnType("V")
}

val sharedExtensionPatch = sharedExtensionPatch("arsound", applicationOnCreateHook)
