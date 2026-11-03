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
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode

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
        // with the list applied before (which was arranged the same way).
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

        setupMenuItemsMethod.insertBeforeLastReturnVoid(
            "invoke-static { p1 }, $BOTTOM_TABS->hideButtons(Landroid/view/View;)V",
        )

        libraryMenuMethod.addInstructions(0, "invoke-static { p1 }, $LIBRARY_SEARCH_BUTTON->onPrepareMenu(Landroid/view/Menu;)V")
    }
}
