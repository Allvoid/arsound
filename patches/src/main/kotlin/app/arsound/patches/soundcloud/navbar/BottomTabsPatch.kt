package app.arsound.patches.soundcloud.navbar

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.resources.ensureValuesFile
import app.arsound.patches.soundcloud.misc.hooks.insertBeforeLastReturnVoid
import app.arsound.patches.soundcloud.misc.hooks.wrapLastReturnedObject
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.ResourceGroup
import app.arsound.util.copyResources
import app.arsound.util.getNode
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val BOTTOM_TABS = "Lapp/revanced/extension/soundcloud/navbar/BottomTabs;"
private const val LIBRARY_SEARCH_BUTTON = "Lapp/revanced/extension/soundcloud/navbar/LibrarySearchButton;"

private const val ARRANGE = "$BOTTOM_TABS->arrange(Ljava/util/List;)Ljava/util/List;"

/** The tabs the bar starts with, before the server is asked for the Upgrade tab. */
private val BytecodePatchContext.initialTargetsMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/listeners/navigation/NavigationTargetsProvider;")
    name("a")
    returnType("Ljava/util/List;")
    parameterTypes()
}

/** Applies a new list of tabs: the model of the bar gets it, then the navigation controller. */
private val BytecodePatchContext.applyTargetsMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/main/MainNavigationPresenter;")
    name("a")
    returnType("Ljava/lang/Object;")
    parameterTypes(
        "Lcom/soundcloud/android/ui/main/MainActivity;",
        "Ljava/util/List;",
        "Lkotlin/coroutines/jvm/internal/ContinuationImpl;",
    )
}

/** Builds the buttons of the bar from the model. */
private val BytecodePatchContext.setupMenuItemsMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/main/MainNavigationView;")
    name("setupMenuItems")
    returnType("V")
    parameterTypes("Lcom/google/android/material/navigation/NavigationBarView;")
}

/** Makes the navigation controller: the fragment holder gets one stack of screens per tab, a fixed number of them. */
private val BytecodePatchContext.initNavControllerMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/listeners/navigation/MainNavController;")
    name("j")
    returnType("V")
    parameterTypes("Lcom/soundcloud/android/ui/main/MainActivity;", "Landroid/os/Bundle;")
}

/** The most tabs the bar can hold: the stock four, the Upgrade tab and "My Wave". */
private const val MAX_TABS = 6

/** SoundCloud makes five stacks of screens (four tabs and Upgrade); with "My Wave" the Upgrade tab, sixth, has none. */
private const val STOCK_STACKS = 5

private val BytecodePatchContext.libraryMenuMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/features/library/LibraryFragment;")
    name("onPrepareOptionsMenu")
    returnType("V")
    parameterTypes("Landroid/view/Menu;")
}

/** The title and the icon of Arsound's "My Wave" tab, which the bar reads as resources. */
private val waveTabResourcePatch = resourcePatch {
    apply {
        copyResources(
            "soundcloud/navbar",
            ResourceGroup("drawable", "arsound_tab_wave.xml", "arsound_tab_wave_plain.xml", "arsound_tab_wave_active.xml"),
        )
        mapOf("values" to "My Wave", "values-ru" to "Моя волна").forEach { (folder, title) ->
            val path = "res/$folder/strings.xml"
            ensureValuesFile(path)
            document(path).use { document ->
                document.getNode("resources").appendChild(
                    document.createElement("string").apply {
                        setAttribute("name", "arsound_tab_wave")
                        textContent = title
                    },
                )
            }
        }
    }
}

/**
 * Tabs of the bottom bar the user can take out (Home, Feed, Search): the tabs that stay are put first in the list the
 * bar and the navigation controller are made from, the hidden ones last, and the buttons of the hidden ones are hidden.
 * With the Search tab out, a search button stands in the top bar of the Library. Arsound's "My Wave" tab
 * (`WaveNavigationTarget`) is put after Home in the same lists. Part of the "Arsound" patch, not shown on its own.
 */
val bottomTabsPatch = bytecodePatch {
    dependsOn(settingsPatch, waveTabResourcePatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        initialTargetsMethod.wrapLastReturnedObject(ARRANGE)

        // The list the controller and the model are given after the server answered: arranged before it is compared
        // with the list applied before (which was arranged the same way). By the method's signature p3 is the
        // continuation, but at the comparison the register holds the new list: the method puts the answer there
        // ("move-result-object p3", "check-cast p3, Ljava/util/List;") right before "areEqual(p3, p2)".
        val compare = applyTargetsMethod.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_STATIC && methodReference?.toString() ==
                "Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z"
        }
        applyTargetsMethod.addInstructions(
            compare,
            """
                invoke-static { p3 }, $ARRANGE
                move-result-object p3
            """,
        )
        // The comparison itself: lists that differ only by the Upgrade tab (which the bar drops anyway) are the same, so the
        // bar is not rebuilt for nothing. A rebuild puts the highlight back on Home while another tab is open.
        // The two inserted instructions are the call and its move-result: areEqual moved down by two.
        val areEqualIndex = compare + 2
        check(applyTargetsMethod.getInstruction(areEqualIndex).methodReference?.name == "areEqual") {
            "The comparison of the tab lists is not where it was expected"
        }
        applyTargetsMethod.replaceInstruction(
            areEqualIndex,
            "invoke-static { p3, p2 }, $BOTTOM_TABS->sameTabs(Ljava/lang/Object;Ljava/lang/Object;)Z",
        )

        // The selected tab is remembered before the menu is cleared and given back after it is rebuilt.
        setupMenuItemsMethod.addInstructions(
            0,
            "invoke-static { p1 }, $BOTTOM_TABS->rememberSelection(Landroid/view/View;)V",
        )

        setupMenuItemsMethod.insertBeforeLastReturnVoid(
            "invoke-static { p1 }, $BOTTOM_TABS->hideButtons(Landroid/view/View;)V",
        )

        // One stack of screens per tab: "My Wave" is a sixth tab next to the stock five, and a tap on the Upgrade tab
        // (index 5) ended in IndexOutOfBoundsException "Can't switch to a tab that hasn't been initialized".
        initNavControllerMethod.apply {
            val count = implementation!!.instructions.withIndex().firstOrNull { (index, instruction) ->
                instruction.opcode == Opcode.CONST_4 &&
                    (instruction as NarrowLiteralInstruction).narrowLiteral == STOCK_STACKS &&
                    getInstruction(index + 1).opcode == Opcode.IF_GE &&
                    getInstruction(index + 2).let {
                        it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == "Ljava/util/Stack;"
                    }
            }?.index ?: throw PatchException("The number of stacks of the navigation controller is not where expected")
            val register = getInstruction<OneRegisterInstruction>(count).registerA
            replaceInstruction(count, "const/4 v$register, $MAX_TABS")
        }

        libraryMenuMethod.addInstructions(0, "invoke-static { p1 }, $LIBRARY_SEARCH_BUTTON->onPrepareMenu(Landroid/view/Menu;)V")
    }
}
