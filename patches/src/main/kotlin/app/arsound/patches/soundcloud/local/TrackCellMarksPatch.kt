package app.arsound.patches.soundcloud.local

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.arsound.patches.soundcloud.misc.hooks.GuardExit
import app.arsound.patches.soundcloud.misc.hooks.insertGuard
import app.arsound.patches.soundcloud.misc.hooks.wrapEveryReturn
import app.arsound.patches.soundcloud.misc.resources.ensureValuesFile
import app.arsound.util.getNode
import app.arsound.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/local/TrackCellMarks;"

private const val QUALITY_MARKS = "Lapp/revanced/extension/soundcloud/local/QualityMarks;"

private const val CELL_ICON_DESCRIPTOR = "Lcom/soundcloud/android/ui/components/compose/listviews/CellIcon;"

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

/** Builds the line under the title of a track in the lists: plays, length and the like. */
private val BytecodePatchContext.trackLineMethod by gettingFirstMethodDeclaratively {
    name("e")
    definingClass("Lcom/soundcloud/android/uievo/statemappers/MetaLabelsKt;")
}

private val importedTrackStringPatch = resourcePatch {
    apply {
        mapOf("values" to "Imported track", "values-ru" to "Импортированный трек").forEach { (folder, text) ->
            val path = "res/$folder/strings.xml"
            ensureValuesFile(path)
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
 * tracks deleted on SoundCloud. In every list, the quality of an imported track under its title.
 * Part of "Local music".
 */
internal val trackCellMarksPatch = bytecodePatch {
    dependsOn(importedTrackStringPatch)

    apply {
        // The registers of this method are above v15; the mark goes in at each of its many returns.
        trackLineMethod.apply {
            addInstructions(0, "invoke-static/range { p0 .. p0 }, $QUALITY_MARKS->onLineStart(Ljava/lang/Object;)V")
            wrapEveryReturn(
                Opcode.RETURN_OBJECT,
                "$QUALITY_MARKS->withMark(Ljava/lang/Object;)Ljava/lang/Object;",
                castTo = "Lcom/soundcloud/android/ui/components/labels/MetaLabel\$ViewState;",
                useRange = true,
            )
        }

        // The icon is a new value of the CellIcon enum, created by the extension.
        firstClassDef(CELL_ICON_DESCRIPTOR).methods.first { it.name == "<init>" }.apply {
            accessFlags = (accessFlags and AccessFlags.PRIVATE.value.inv()) or AccessFlags.PUBLIC.value
        }

        playlistTrackCellEndMethod.apply {
            val overflowIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET_OBJECT && fieldReference?.let {
                    it.definingClass == CELL_ICON_DESCRIPTOR && it.name == "d"
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

        playlistTrackCellTapMethod.insertGuard(
            "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->onTap(Ljava/lang/Object;)Z",
            GuardExit.ReturnUnit,
        )
    }
}
