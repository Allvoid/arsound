package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.hooks.hookConstructorEnd
import app.arsound.patches.soundcloud.misc.hooks.wrapEveryReturn
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/player/PlayerComments;"

private const val PLAYER_SETTINGS = "Lcom/soundcloud/android/settings/player/PlayerSettings;"

private val BytecodePatchContext.playerSettingsConstructorMethod by gettingFirstMethodDeclaratively {
    definingClass(PLAYER_SETTINGS)
    name("<init>")
}

/** Whether comments pop up over the full screen player: SoundCloud's own setting "player_popup_comments". */
private val BytecodePatchContext.popupCommentsMethod by gettingFirstMethodDeclaratively("player_popup_comments") {
    definingClass(PLAYER_SETTINGS)
    returnType("Z")
    parameterTypes()
}

/**
 * Hide pop-up comments: the comments of listeners no longer pop up over the player while a track plays, and are not
 * marked on the waveform. Part of the "Arsound" patch, not shown on its own.
 */
val playerCommentsPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        playerSettingsConstructorMethod.hookConstructorEnd("$EXTENSION_CLASS_DESCRIPTOR->setPlayerSettings(Ljava/lang/Object;)V")
        popupCommentsMethod.wrapEveryReturn(Opcode.RETURN, "$EXTENSION_CLASS_DESCRIPTOR->showPopups(Z)Z")
    }
}
