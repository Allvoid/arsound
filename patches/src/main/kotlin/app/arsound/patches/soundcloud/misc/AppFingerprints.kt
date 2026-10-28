package app.arsound.patches.soundcloud.misc

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

/**
 * The start of the application, before any activity exists. Several patches add a call at its start; they run in the
 * order they are depended on, and the last one added ends up first.
 */
internal val BytecodePatchContext.applicationOnCreateMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/app/RealSoundCloudApplication;")
    name("onCreate")
    returnType("V")
    parameterTypes()
}
