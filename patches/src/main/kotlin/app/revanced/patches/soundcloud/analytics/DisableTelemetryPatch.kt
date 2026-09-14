package app.revanced.patches.soundcloud.analytics

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.soundcloud.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/analytics/DisableTelemetryPatch;"

@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Adds an option to disable SoundCloud's telemetry system.",
) {
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
