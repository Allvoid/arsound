package app.arsound.patches.soundcloud.misc.settings

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

/**
 * The Compose function that lays out the rows of the main settings screen:
 * `SettingsScreen(state, onBack, onAction, composer, changed)`.
 */
internal val BytecodePatchContext.settingsScreenContentMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/settings/main/SettingsScreenKt;")
    returnType("V")
    parameterTypes(
        "Lcom/soundcloud/android/settings/main/SettingsState;",
        "Lkotlin/jvm/functions/Function0;",
        "Lkotlin/jvm/functions/Function1;",
        "Landroidx/compose/runtime/Composer;",
        "I",
    )
}

/**
 * The click of the "Language" row of the "Basic settings" screen (a coroutine of `BasicSettingsFragment`): it opens the
 * system's page of app languages for the hard-coded package `com.soundcloud.android`.
 */
internal val BytecodePatchContext.languageRowClickMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/settings/basic/BasicSettingsFragment\$onCreateView\$1\$1\$1\$2\$1;")
    name("invokeSuspend")
}
