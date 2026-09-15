package app.revanced.patches.soundcloud.recommendations

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.revanced.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/recommendations/DuplicateFilter;"

private const val AUTOPLAY_CLASS =
    "Lcom/soundcloud/android/features/playqueue/extender/PlayQueueExtenderOperations\$loadRelatedForRemoteTrack\$1;"

/** Turns the entities of any home screen section into its items; every section builder calls it. */
private val BytecodePatchContext.sectionItemsMethod by gettingFirstMethodDeclaratively {
    name("c")
    definingClass("Lcom/soundcloud/android/sections/domain/SectionKt;")
    returnType("Ljava/util/ArrayList;")
}

/** Turns related tracks of the finished track into play queue items for autoplay. */
private val BytecodePatchContext.autoplayItemsMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass(AUTOPLAY_CLASS)
}

@Suppress("unused")
val duplicateFilterPatch = bytecodePatch(
    name = "Hide duplicate recommendations",
    description = "Adds an option to hide re-uploads of the same song in home sections and autoplay.",
) {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        sectionItemsMethod.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->filterSectionEntities(Ljava/util/List;)Ljava/util/List;
                move-result-object p0
            """,
        )

        autoplayItemsMethod.apply {
            // invoke-interface {v1}, Iterable;->iterator(); move-result-object v17
            val iteratorIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE &&
                    (this as ReferenceInstruction).reference.toString() == "Ljava/lang/Iterable;->iterator()Ljava/util/Iterator;"
            }
            addInstructions(
                iteratorIndex + 2,
                """
                    iget-object v4, v0, $AUTOPLAY_CLASS->a:Lcom/soundcloud/android/foundation/domain/Urn;
                    invoke-static { v1, v4 }, $EXTENSION_CLASS_DESCRIPTOR->filterAutoplay(Ljava/lang/Iterable;Ljava/lang/Object;)Ljava/util/Iterator;
                    move-result-object v17
                """,
            )
        }
    }
}
