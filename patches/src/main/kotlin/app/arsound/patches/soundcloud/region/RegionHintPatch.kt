package app.arsound.patches.soundcloud.region

import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/region/RegionHint;"

private const val META_LABEL_UTILS_CLASS = "Lcom/soundcloud/android/ui/utils/MetaLabelUtilsKt;"

private const val COLLECTIONS_CLASS = "Lkotlin/collections/CollectionsKt;"

/**
 * Draws the line of labels under a track title (Compose): "Not available in your country", likes, duration and so
 * on. The labels are turned into a list of pieces that is laid out in a flow row.
 */
private val BytecodePatchContext.composeMetaLabelMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/components/compose/labels/MetaLabelKt;")
    name("a")
    returnType("V")
    parameterTypes(
        "Lcom/soundcloud/android/ui/components/labels/MetaLabel\$ViewState;",
        "Landroidx/compose/ui/Modifier;",
        "Z",
        "Lkotlin/jvm/functions/Function0;",
        "Landroidx/compose/runtime/Composer;",
        "I",
        "I",
    )
}

/** The same line as a View (the cells made from XML layouts): the labels are set with a list of label types. */
private val BytecodePatchContext.viewMetaLabelMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/components/labels/MetaLabel;")
    name("n")
    returnType("V")
    parameterTypes("Ljava/util/List;")
}

/** The banner "This track is not available in your country" at the top of a track page. */
private val BytecodePatchContext.geoBlockedBannerMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/trackpage/renderers/GeoBlockedBannerRendererKt;")
    name("a")
    returnType("V")
    parameterTypes("Landroidx/compose/runtime/Composer;", "I")
}

/** The register of the value a call returns, from the `move-result-object` right after the call. */
private fun MutableMethod.resultRegister(callIndex: Int): Int {
    val register = getInstruction<OneRegisterInstruction>(callIndex + 1).registerA
    // invoke-static with a list of registers can only name the first sixteen.
    require(register < 16) { "The result of the call at $callIndex is in v$register" }
    return register
}

private fun MutableMethod.indexOfDropCall() = indexOfFirstInstructionOrThrow {
    opcode == Opcode.INVOKE_STATIC && methodReference?.let {
        it.definingClass == COLLECTIONS_CLASS && it.name == "drop"
    } == true
}

/**
 * Region hint: a wiggling "?" after the label "Not available in your country" on tracks, which opens an
 * explanation of the label with what helps. Part of the "Network" patch group, not shown on its own.
 *
 * Both ways SoundCloud draws the label line get the same two calls: one that tells the extension which labels the
 * line has, and one, right before the line is laid out, that adds the badge to its end if the lock label is among
 * them. The banner of the track page gets the badge after its text.
 */
val regionHintPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.10.14-release"))

    apply {
        composeMetaLabelMethod.apply {
            val labelsIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC && methodReference?.let {
                    it.definingClass == META_LABEL_UTILS_CLASS && it.name == "a"
                } == true
            }
            val dropIndex = indexOfDropCall()
            val labelsRegister = resultRegister(labelsIndex)
            val piecesRegister = resultRegister(dropIndex)

            // The later edit first, so the earlier index stays where it was found.
            addInstructions(
                dropIndex + 2,
                """
                    invoke-static { v$piecesRegister }, $EXTENSION_CLASS_DESCRIPTOR->appendBadge(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$piecesRegister
                """,
            )
            addInstructions(
                labelsIndex + 2,
                "invoke-static { v$labelsRegister }, $EXTENSION_CLASS_DESCRIPTOR->noteLabels(Ljava/util/List;)V",
            )
        }

        viewMetaLabelMethod.apply {
            val dropIndex = indexOfDropCall()
            val viewsRegister = resultRegister(dropIndex)

            addInstructions(
                dropIndex + 2,
                """
                    invoke-static { v$viewsRegister }, $EXTENSION_CLASS_DESCRIPTOR->appendBadgeView(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$viewsRegister
                """,
            )
            // The label types are the parameter, which is out of reach of a plain invoke.
            addInstructions(
                0,
                "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS_DESCRIPTOR->noteLabels(Ljava/util/List;)V",
            )
        }

        geoBlockedBannerMethod.apply {
            val endRowIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE && methodReference?.let {
                    it.definingClass == "Landroidx/compose/runtime/Composer;" && it.name == "h"
                } == true
            }
            val composerRegister = getInstruction<FiveRegisterInstruction>(endRowIndex).registerC
            addInstructions(
                endRowIndex,
                "invoke-static { v$composerRegister }, $EXTENSION_CLASS_DESCRIPTOR->bannerBadge(Landroidx/compose/runtime/Composer;)V",
            )
        }
    }
}
