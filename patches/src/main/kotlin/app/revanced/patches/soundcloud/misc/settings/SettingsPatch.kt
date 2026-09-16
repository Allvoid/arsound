package app.revanced.patches.soundcloud.misc.settings

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.soundcloud.misc.extension.sharedExtensionPatch
import app.revanced.util.getNode
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val SETTINGS_ACTIVITY_CLASS =
    "app.revanced.extension.soundcloud.settings.ReVancedSettingsActivity"

private const val SETTINGS_ENTRY_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/settings/SettingsEntry;"

private val settingsActivityPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { document ->
            document.getNode("application").appendChild(
                document.createElement("activity").apply {
                    setAttribute("android:name", SETTINGS_ACTIVITY_CLASS)
                    setAttribute("android:exported", "false")
                    setAttribute("android:theme", "@style/SoundcloudAppTheme")
                    setAttribute("android:configChanges", "orientation|screenSize|uiMode")
                },
            )
        }
    }
}

val settingsPatch = bytecodePatch(
    name = "Settings",
    description = "Adds a ReVanced menu to the SoundCloud settings, styled like the app.",
) {
    dependsOn(sharedExtensionPatch, settingsActivityPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        settingsScreenContentMethod.apply {
            // The string resource ids are read from the R class of the settings module, not inlined as literals.
            val helpCenterStringIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET && fieldReference?.name == "more_help_center"
            }

            // stringResource(id, composer, changed): the composer of the settings list is its second argument.
            val stringResourceIndex = indexOfFirstInstructionOrThrow(helpCenterStringIndex) {
                opcode == Opcode.INVOKE_STATIC && methodReference?.name == "stringResource"
            }
            val composerRegister = getInstruction<FiveRegisterInstruction>(stringResourceIndex).registerD

            // Add the ReVanced row right above "Help center".
            addInstruction(
                helpCenterStringIndex,
                "invoke-static { v$composerRegister }, " +
                    "$SETTINGS_ENTRY_CLASS_DESCRIPTOR->addEntry(Landroidx/compose/runtime/Composer;)V",
            )
        }

        // Background update check on every launch, shown on the first screen that opens.
        applicationOnCreateMethod.apply {
            addInstruction(
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                "invoke-static { p0 }, Lapp/revanced/extension/soundcloud/update/UpdateChecker;" +
                    "->onApplicationCreate(Landroid/app/Application;)V",
            )
        }
    }
}
