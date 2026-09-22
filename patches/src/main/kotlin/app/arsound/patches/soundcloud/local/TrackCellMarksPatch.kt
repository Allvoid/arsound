package app.arsound.patches.soundcloud.local

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.arsound.util.getNode
import app.arsound.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/local/TrackCellMarks;"

private const val CELL_ICON_CLASS = "Lcom/soundcloud/android/ui/components/compose/listviews/CellIcon;"

/** Composes a track cell of the playlist screen. */
private val BytecodePatchContext.playlistTrackCellMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/playlist/view/renderers/PlaylistTrackItemRenderer\$PlaylistTrackItemViewHolder;")
}

/** The icon buttons at the end of a playlist track cell: "add" for suggestions, otherwise "more". */
private val BytecodePatchContext.playlistTrackCellEndMethod by gettingFirstMethodDeclaratively {
    name("invoke")
    definingClass("Lcom/soundcloud/android/playlist/view/renderers/e;")
}

/** The tap actions of a playlist track cell: 0 plays the track. */
private val BytecodePatchContext.playlistTrackCellTapMethod by gettingFirstMethodDeclaratively {
    name("invoke")
    definingClass("Lcom/soundcloud/android/playlist/view/renderers/c;")
}

private val importedTrackStringPatch = resourcePatch {
    apply {
        mapOf("values" to "Imported track", "values-ru" to "Импортированный трек").forEach { (folder, text) ->
            val path = "res/$folder/strings.xml"
            val file = get(path)
            if (!file.exists()) {
                file.parentFile.mkdirs()
                file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
            }
            document(path).use { document ->
                document.getNode("resources").appendChild(
                    document.createElement("string").apply {
                        setAttribute("name", "arsound_imported_track")
                        textContent = text
                    },
                )
            }
        }
    }
}

/**
 * Track cells of the playlist screen: a note icon next to "more" for imported tracks, greyed-out cells for
 * tracks deleted on SoundCloud. Part of "Local music".
 */
internal val trackCellMarksPatch = bytecodePatch {
    dependsOn(importedTrackStringPatch)

    apply {
        // The icon is a new value of the CellIcon enum, created by the extension.
        firstClassDef(CELL_ICON_CLASS).methods.first { it.name == "<init>" }.apply {
            accessFlags = (accessFlags and AccessFlags.PRIVATE.value.inv()) or AccessFlags.PUBLIC.value
        }

        playlistTrackCellEndMethod.apply {
            val overflowIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET_OBJECT && fieldReference?.let {
                    it.definingClass == CELL_ICON_CLASS && it.name == "d"
                } == true
            }
            // At this point the row scope is in v0 and the composer in v4; v1 is set right after.
            addInstructions(
                overflowIndex,
                """
                    iget-object v1, p0, Lcom/soundcloud/android/playlist/view/renderers/e;->d:Lcom/soundcloud/android/foundation/domain/tracks/TrackItem;
                    invoke-static { v0, v1, v4 }, $EXTENSION_CLASS_DESCRIPTOR->addImportedIcon(Ljava/lang/Object;Ljava/lang/Object;Landroidx/compose/runtime/Composer;)V
                """,
            )
        }

        playlistTrackCellMethod.apply {
            val modifierCallIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC &&
                    methodReference?.definingClass?.endsWith("/UnavailableTrackImpressionModifierKt;") == true
            }
            val trackItemRegister = getInstruction<FiveRegisterInstruction>(modifierCallIndex).registerD
            val modifierRegister = getInstruction<OneRegisterInstruction>(modifierCallIndex + 1).registerA
            addInstructions(
                modifierCallIndex + 2,
                """
                    invoke-static { v$modifierRegister, v$trackItemRegister }, $EXTENSION_CLASS_DESCRIPTOR->cellModifier(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$modifierRegister
                    check-cast v$modifierRegister, Landroidx/compose/ui/Modifier;
                """,
            )
        }

        playlistTrackCellTapMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->onTap(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :original
                sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
                return-object v0
            """,
            ExternalLabel("original", playlistTrackCellTapMethod.getInstruction(0)),
        )
    }
}
