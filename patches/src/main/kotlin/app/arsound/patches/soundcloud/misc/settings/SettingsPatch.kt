package app.arsound.patches.soundcloud.misc.settings

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.arsound.patches.soundcloud.misc.extension.sharedExtensionPatch
import app.arsound.util.getNode
import app.arsound.util.indexOfFirstInstructionOrThrow
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

/** Settings: Adds a ReVanced menu to the SoundCloud settings, styled like the app. Part of the "Arsound" patch, not shown on its own. */
val settingsPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch, settingsActivityPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        settingsScreenContentMethod.apply {
            // The string resource ids are read from the R class of the settings module, not inlined as literals.
            // The first row of the list is "Import my music" (settings_transfer_your_library_title).
            val firstRowStringIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET && fieldReference?.name == "settings_transfer_your_library_title"
            }

            // stringResource(id, composer, changed): the composer of the settings list is its second argument.
            val stringResourceIndex = indexOfFirstInstructionOrThrow(firstRowStringIndex) {
                opcode == Opcode.INVOKE_STATIC && methodReference?.name == "stringResource"
            }
            val composerRegister = getInstruction<FiveRegisterInstruction>(stringResourceIndex).registerD

            // Add the Arsound row at the top of the list, before "Import my music". It goes after the title's
            // move-result: a jump label sits on the sget above, and code inserted there would be jumped over.
            addInstruction(
                stringResourceIndex + 2,
                "invoke-static { v$composerRegister }, " +
                    "$SETTINGS_ENTRY_CLASS_DESCRIPTOR->addEntry(Landroidx/compose/runtime/Composer;)V",
            )
        }

        // Background update check on every launch, shown on the first screen that opens.
        applicationOnCreateMethod.apply {
            // At the start: later in onCreate the p0 register is reused for other objects.
            addInstruction(
                0,
                "invoke-static { p0 }, Lapp/revanced/extension/soundcloud/update/UpdateChecker;" +
                    "->onApplicationCreate(Landroid/app/Application;)V",
            )
        }
    }
}
