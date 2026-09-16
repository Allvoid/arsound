package app.arsound.patches.soundcloud.recommendations

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import app.revanced.patcher.extensions.getInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/recommendations/DuplicateFilter;"

private const val AUTOPLAY_CLASS =
    "Lcom/soundcloud/android/features/playqueue/extender/PlayQueueExtenderOperations\$loadRelatedForRemoteTrack\$1;"

/**
 * Builds the items of all home screen sections (server-driven). Shelves, carousels and galleries
 * read their ordered entity list from a field right before mapping it to items.
 */
private val BytecodePatchContext.sectionItemsMethod by gettingFirstMethodDeclaratively {
    name("e")
    definingClass("Lcom/soundcloud/android/sections/ui/models/SectionsViewStateKt;")
    returnType("Ljava/util/ArrayList;")
}

/** Turns related tracks of the finished track into play queue items for autoplay. */
private val BytecodePatchContext.autoplayItemsMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass(AUTOPLAY_CLASS)
}

/** Constructors of server-driven home screen views that hold a list of items. */
private val BytecodePatchContext.homeCarouselConstructor by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/sdui/components/SDUIView\$Carousel;")
}
private val BytecodePatchContext.homeGalleryConstructor by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/sdui/components/SDUIView\$Gallery;")
}
private val BytecodePatchContext.homeSuggestionsConstructor by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/sdui/components/SDUIView\$Suggestions;")
}

/** Hide duplicate recommendations: Adds an option to hide re-uploads of the same song in home sections and autoplay. Part of the "Arsound" patch, not shown on its own. */
val duplicateFilterPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        // Home screen (server-driven UI): the item list is filtered in place before the view object keeps it.
        listOf(homeCarouselConstructor, homeGalleryConstructor, homeSuggestionsConstructor).forEach { constructor ->
            // p1 is the first parameter.
            val parameter = constructor.parameterTypes.indexOfFirst { it.toString() == "Ljava/util/ArrayList;" } + 1
            constructor.addInstructions(
                0,
                "invoke-static { p$parameter }, Lapp/revanced/extension/soundcloud/recommendations/DuplicateFilter;->filterHomeViews(Ljava/util/ArrayList;)V",
            )
        }

        sectionItemsMethod.apply {
            val listReads = implementation!!.instructions.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.IGET_OBJECT &&
                    (instruction as ReferenceInstruction).reference.toString().let {
                        it.startsWith("Lcom/soundcloud/android/sections/domain/Section$") && it.endsWith(":Ljava/util/List;")
                    }
            }.map { it.index }
            // From the end, so earlier indexes stay valid.
            listReads.reversed().forEach { index ->
                val register = getInstruction<TwoRegisterInstruction>(index).registerA
                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->filterSectionEntities(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """,
                )
            }
        }

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
