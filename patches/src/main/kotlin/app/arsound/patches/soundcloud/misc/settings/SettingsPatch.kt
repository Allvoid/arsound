package app.arsound.patches.soundcloud.misc.settings

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.applicationOnCreateMethod
import app.arsound.patches.soundcloud.misc.extension.sharedExtensionPatch
import app.arsound.patches.soundcloud.misc.manifest.addApplicationComponent
import app.arsound.util.ResourceGroup
import app.arsound.util.copyResources
import app.arsound.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val SETTINGS_ACTIVITY_CLASS =
    "app.revanced.extension.soundcloud.settings.ReVancedSettingsActivity"

private const val SETTINGS_ENTRY_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/settings/SettingsEntry;"

private const val LANGUAGE_CHOICE_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/settings/LanguageChoice;"

private val settingsActivityPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { document ->
            document.addApplicationComponent(
                "activity",
                "android:name" to SETTINGS_ACTIVITY_CLASS,
                "android:exported" to "false",
                "android:theme" to "@style/SoundcloudAppTheme",
                "android:configChanges" to "orientation|screenSize|uiMode",
            )
        }
    }
}

/**
 * The pictures of the settings rows (a palette, a globe, a shield...): our own vector drawables, white, tinted by the
 * rows in code. A row falls back to a SoundCloud icon, or to none, when one of them is missing (SettingsIcons).
 */
private val settingsIconsResourcePatch = resourcePatch {
    apply {
        copyResources(
            "soundcloud/settings",
            ResourceGroup(
                "drawable",
                "arsound_set_adult.xml", "arsound_set_ads.xml", "arsound_set_animation.xml", "arsound_set_appearance.xml",
                "arsound_set_appicon.xml", "arsound_set_battery.xml", "arsound_set_cache.xml", "arsound_set_developer.xml",
                "arsound_set_dns.xml", "arsound_set_folder.xml", "arsound_set_key.xml", "arsound_set_language.xml",
                "arsound_set_mix.xml", "arsound_set_privacy.xml", "arsound_set_reset.xml", "arsound_set_storage.xml",
                "arsound_set_update.xml", "arsound_set_widget.xml",
            ),
        )
    }
}

/** Settings: Adds a ReVanced menu to the SoundCloud settings, styled like the app. Part of the "Arsound" patch, not shown on its own. */
val settingsPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch, settingsActivityPatch, settingsIconsResourcePatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

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

        // "Language" on SoundCloud's "Basic settings": the system's page of app languages for the original app's package
        // (nothing on a phone without it, the wrong app's languages with it) is replaced by Arsound's own choice.
        languageRowClickMethod.apply {
            val start = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.name == "startActivity"
            }
            val fragment = getInstruction<FiveRegisterInstruction>(start).registerC
            replaceInstruction(
                start,
                "invoke-static { v$fragment }, $LANGUAGE_CHOICE_CLASS_DESCRIPTOR->openFromSoundCloud(Ljava/lang/Object;)V",
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
