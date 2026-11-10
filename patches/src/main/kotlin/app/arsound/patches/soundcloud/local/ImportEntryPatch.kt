package app.arsound.patches.soundcloud.local

import app.arsound.util.ResourceGroup
import app.arsound.util.copyResources
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.arsound.util.indexOfFirstInstructionReversedOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.extensions.typeReference
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.method
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val MUSIC_API = "Lcom/soundcloud/android/playlistimport/musicapi/data/MusicApi;"
private const val TRANSFER_TILES = "Lapp/revanced/extension/soundcloud/settings/TransferTiles;"

private val BytecodePatchContext.playlistImportSettingsScreenMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/settings/playlistimport/PlaylistImportSettingsScreenKt;")
}

/** The list of "Import from another app": SoundCloud's apps, cut into rows of two or four tiles. */
private val BytecodePatchContext.importPlatformListMethod by gettingFirstMethodDeclaratively {
    name("invoke")
    returnType("Ljava/lang/Object;")
    parameterTypes("Ljava/lang/Object;")
    instructions(
        method { definingClass == MUSIC_API && name == "getEntries" },
    )
}

/** The pictures of the Yandex Music, VK Music and YouTube Music tiles, Russian and English, as big as SoundCloud's own tiles. */
private val transferTilesResourcePatch = resourcePatch {
    apply {
        copyResources(
            "soundcloud/settings",
            ResourceGroup(
                "drawable-nodpi",
                "arsound_yandex_music_ru.webp", "arsound_yandex_music_en.webp",
                "arsound_vk_music_ru.webp", "arsound_vk_music_en.webp",
                "arsound_youtube_music_ru.webp", "arsound_youtube_music_en.webp",
            ),
        )
    }
}

/**
 * The rows "Import files from this phone" and "Transfer from Yandex Music, VK and YouTube Music" on SoundCloud's
 * "Import my music" screen, and the tiles of Yandex Music, VK Music and YouTube Music among the apps of its "Import from another app".
 */
internal val importEntryPatch = bytecodePatch {
    dependsOn(transferTilesResourcePatch)

    apply {
        // Arsound's rows below "Manage imported likes" on SoundCloud's "Import my music" screen.
        playlistImportSettingsScreenMethod.apply {
            val revertTitleIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET && fieldReference?.name == "playlist_import_settings_revert_transfer"
            }
            val rowIndex = indexOfFirstInstructionOrThrow(revertTitleIndex) {
                opcode == Opcode.INVOKE_STATIC_RANGE && methodReference?.definingClass?.endsWith("ActionListStandardKt;") == true
            }
            // ActionListStandard(changed, defaults, composer, modifier, title, subtitle, onClick).
            val composerRegister = getInstruction<RegisterRangeInstruction>(rowIndex).startRegister + 2
            addInstruction(
                rowIndex + 1,
                "invoke-static { v$composerRegister }, " +
                    "Lapp/revanced/extension/soundcloud/settings/SettingsEntry;->addImportEntry(Landroidx/compose/runtime/Composer;)V",
            )
        }

        // The tile: the list gets it as one more app when it is cut into rows. The cut itself is replaced, since a jump
        // label sits on it and code inserted before it would be jumped over.
        val rowLambda = importPlatformListMethod.run {
            val chunkedIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC && methodReference?.let {
                    it.definingClass == "Lkotlin/collections/CollectionsKt;" && it.name == "chunked"
                } == true
            }
            val chunked = getInstruction<FiveRegisterInstruction>(chunkedIndex)
            replaceInstruction(
                chunkedIndex,
                "invoke-static { v${chunked.registerC}, v${chunked.registerD} }, " +
                    "$TRANSFER_TILES->rows(Ljava/lang/Iterable;I)Ljava/util/List;",
            )
            // Each row is drawn by a lambda made right after the cut.
            getInstruction(indexOfFirstInstructionOrThrow(chunkedIndex) { opcode == Opcode.NEW_INSTANCE }).typeReference!!.type
        }

        // The row draws its apps one by one; the tile is drawn by the extension, which hands SoundCloud null for it.
        firstClassDef(rowLambda).methods.first { it.name == "invoke" && it.parameterTypes.size == 3 }.apply {
            val logosIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET_OBJECT && fieldReference?.let {
                    it.definingClass.endsWith("/ImportPlatformsScreenKt\$WhenMappings;") && it.name == "\$EnumSwitchMapping\$0"
                } == true
            }
            val castIndex = indexOfFirstInstructionReversedOrThrow(logosIndex) {
                opcode == Opcode.CHECK_CAST && typeReference?.type == MUSIC_API
            }
            val nextAppIndex = indexOfFirstInstructionReversedOrThrow(castIndex) {
                opcode == Opcode.INVOKE_INTERFACE && methodReference?.let {
                    it.definingClass == "Ljava/util/Iterator;" && it.name == "hasNext"
                } == true
            }
            val appRegister = getInstruction<OneRegisterInstruction>(castIndex).registerA
            // RowScope.weight(modifier, 1f) of SoundCloud's tile: its first register is the scope of the row.
            val rowScopeRegister = getInstruction<RegisterRangeInstruction>(
                indexOfFirstInstructionOrThrow(castIndex) {
                    opcode == Opcode.INVOKE_STATIC_RANGE && methodReference?.name == "weight\$default"
                },
            ).startRegister
            val composerRegister = getInstruction<FiveRegisterInstruction>(
                indexOfFirstInstructionOrThrow(castIndex) {
                    opcode == Opcode.INVOKE_INTERFACE && methodReference?.definingClass == "Landroidx/compose/runtime/Composer;"
                },
            ).registerC
            addInstructionsWithLabels(
                castIndex,
                """
                    invoke-static { v$appRegister, v$rowScopeRegister, v$composerRegister }, $TRANSFER_TILES->drawTile(Ljava/lang/Object;Ljava/lang/Object;Landroidx/compose/runtime/Composer;)Ljava/lang/Object;
                    move-result-object v$appRegister
                    if-eqz v$appRegister, :next_app
                """,
                ExternalLabel("next_app", getInstruction(nextAppIndex)),
            )
        }
    }
}
