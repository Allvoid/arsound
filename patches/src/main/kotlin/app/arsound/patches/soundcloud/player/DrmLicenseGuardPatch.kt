package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.hooks.GuardExit
import app.arsound.patches.soundcloud.misc.hooks.insertGuard
import app.arsound.patches.soundcloud.misc.hooks.wrapEveryReturn
import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/player/DrmLicenseGuard;"

private const val REPOSITORY = "Lcom/soundcloud/android/drm/shortlived/ShortLivedLicenseRepository;"

/**
 * The saved licence (key set id) of a stream from SoundCloud's own database, or null when there is none or it has
 * expired. The first return is the found one.
 */
private val BytecodePatchContext.cachedLicenseMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass(REPOSITORY)
    parameterTypes("Ljava/lang/String;")
    returnType("[B")
}

/**
 * The saved licence for a stream, or null after asking a background worker to download one. Playback of encrypted
 * tracks takes the answer.
 */
private val BytecodePatchContext.licenseForStreamMethod by gettingFirstMethodDeclaratively {
    name("b")
    definingClass(REPOSITORY)
    parameterTypes("Ljava/lang/String;")
    returnType("[B")
}

/**
 * Encrypted tracks: a saved licence that the phone's DRM store no longer has is not given to the player (it made
 * the track pause at once, on every tap), and a phone that loses licences is not given more of them to save. Part of
 * the "Arsound" patch, not shown on its own.
 */
val drmLicenseGuardPatch = bytecodePatch {
    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        // Both returns: the one with the found licence is checked, the one with null passes through unchanged.
        cachedLicenseMethod.wrapEveryReturn(
            Opcode.RETURN_OBJECT,
            "$EXTENSION_CLASS_DESCRIPTOR->restorable([B)[B",
        )

        // No new licence is saved once one was lost: v0 is free at the start, the stream address stays in p1.
        licenseForStreamMethod.insertGuard(
            "invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->storeLosesLicences()Z",
            GuardExit.Custom("move-result", "const/4 v0, 0x0\nreturn-object v0"),
        )
    }
}
