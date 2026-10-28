package app.arsound.patches.soundcloud.upsell

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType

private val BytecodePatchContext.setupNavigationModelMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/main/MainNavigationView;")
    name("setupNavigationModel")
    returnType("V")
    parameterTypes("Lcom/soundcloud/android/architecture/view/RootActivity;", "Ljava/util/List;")
}

/**
 * Called when a bottom bar tab is tapped. Used only to record which position was tapped,
 * to catch the rare case of a tab opening the screen of another one.
 */
private val BytecodePatchContext.navigationItemSelectedMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/ui/main/MainNavigationView;")
    name("createOnNavigationItemSelectedListener\$lambda\$0")
    returnType("Z")
    parameterTypes(
        "Lcom/soundcloud/android/ui/main/MainNavigationView;",
        "Lcom/soundcloud/android/architecture/view/RootActivity;",
        "Landroid/view/MenuItem;",
    )
}

/** The Upgrade tab of the bottom bar, which has nothing to sell here. */
internal val navigationTabsPatch = bytecodePatch {
    apply {
        // Drop the Upgrade tab before the bottom bar menu is built, so the other tabs share its space.
        setupNavigationModelMethod.addInstructions(
            0,
            """
                invoke-static { p2 }, $HIDE_PAYWALL_DESCRIPTOR->filterNavigationTabs(Ljava/util/List;)Ljava/util/List;
                move-result-object p2
            """,
        )

        // Records every tab tap in the log. The bottom bar addresses a tab by its position in the tab
        // list, so a stale position shows up here as a tap that lands on the wrong screen.
        navigationItemSelectedMethod.addInstructions(
            0,
            """
                invoke-static { p0, p2 }, $HIDE_PAYWALL_DESCRIPTOR->logNavigationTap(Ljava/lang/Object;Landroid/view/MenuItem;)V
            """,
        )
    }
}
