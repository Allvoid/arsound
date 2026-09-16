package app.arsound.patches.soundcloud.analytics

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.arsound.patches.soundcloud.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/analytics/DisableTelemetryPatch;"

/** Disable telemetry: Adds an option to disable SoundCloud's telemetry system. Part of the "Arsound" patch, not shown on its own. */
val disableTelemetryPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith(
        "com.soundcloud.android"("2025.05.27-release", "2026.09.02-release"),
    )

    apply {
        // An empty "backend" argument aborts the initializer, so the extension
        // replaces the argument when telemetry is disabled in the ReVanced settings.
        createTrackingApiMethod.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->getTrackingBackend(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
            """,
        )
    }
}
