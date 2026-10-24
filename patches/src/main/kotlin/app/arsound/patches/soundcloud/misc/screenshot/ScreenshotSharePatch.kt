package app.arsound.patches.soundcloud.misc.screenshot

import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/screenshot/ScreenshotShare;"

/**
 * Captures the screen and opens the "Share screenshot" sheet. Called from the screenshot callback of the full screen
 * player and from the one of the screen under it, both at once.
 */
private val BytecodePatchContext.shareScreenshotMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/architecture/screenshots/ScreenshotsController;")
    name("e")
    parameterTypes(
        "Landroid/view/Window;",
        "Lcom/soundcloud/android/architecture/screenshots/ScreenToCapture;",
        "Lcom/soundcloud/android/architecture/screenshots/ScreenshotCapturedEvent;",
    )
}

/** Registers a screen's screenshot callback with the activity (static, Android 14+). */
private val BytecodePatchContext.registerCallbackMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/architecture/screenshots/ScreenshotsController;")
    name("d")
    parameterTypes("Landroidx/fragment/app/FragmentActivity;", "Landroid/app/Activity\$ScreenCaptureCallback;")
}

/** Unregisters it again. */
private val BytecodePatchContext.unregisterCallbackMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/architecture/screenshots/ScreenshotsController;")
    name("f")
    parameterTypes("Landroidx/fragment/app/FragmentActivity;", "Landroid/app/Activity\$ScreenCaptureCallback;")
}

/**
 * Screenshot share sheet: off by default; when turned on, it opens once per screenshot instead of twice.
 * Part of the "Arsound" patch, not shown on its own.
 */
val screenshotSharePatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.10.14-release"))

    apply {
        shareScreenshotMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->shouldShare()Z
                move-result v0
                if-nez v0, :share
                return-void
            """,
            ExternalLabel("share", shareScreenshotMethod.getInstruction(0)),
        )
        // With the sheet off the screens do not watch for screenshots at all, so Android does not report the app
        // as one that detected them.
        mapOf(registerCallbackMethod to "register", unregisterCallbackMethod to "unregister").forEach { (method, call) ->
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->$call(Ljava/lang/Object;)Z
                    move-result v0
                    if-nez v0, :proceed
                    return-void
                """,
                ExternalLabel("proceed", method.getInstruction(0)),
            )
        }
    }
}
