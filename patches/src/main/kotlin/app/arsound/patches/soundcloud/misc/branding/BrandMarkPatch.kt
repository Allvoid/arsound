package app.arsound.patches.soundcloud.misc.branding

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.util.findInstructionIndicesReversed
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val BRAND_MARK = "Lapp/revanced/extension/soundcloud/branding/BrandMark;"

/** SoundCloud's small icon of every notification, which the branding makes the Arsound letter. */
private const val NOTIFICATION_ICON = "Lcom/soundcloud/android/ui/components/R\$drawable;->ic_logo_cloud_light:I"

private const val PLAYER_NOTIFICATION = "Lcom/soundcloud/android/playback/PlaybackNotificationProvider;"

/** The field the player's notification keeps its small icon in, set once when the class is loaded. */
private const val PLAYER_NOTIFICATION_ICON = "$PLAYER_NOTIFICATION->e:I"

/**
 * Where SoundCloud builds its notifications: the player (also what a "live island" beside the clock shows), the
 * downloads, an upload, a message, a follow that failed, a push message and the cast notification.
 */
private val NOTIFICATION_CLASSES = listOf(
    PLAYER_NOTIFICATION,
    "Lcom/soundcloud/android/offline/DownloadNotificationController;",
    "Lcom/soundcloud/android/creators/uploadv2/data/UploaderNotificationController;",
    "Lcom/soundcloud/android/messages/notification/DefaultMessagingNotificationController;",
    "Lcom/soundcloud/android/sync/affiliations/MyFollowingsSyncer\$FollowErrorNotificationBuilder;",
    "Lcom/soundcloud/android/moengage/DefaultMoEngageSdk;",
    "Lcom/soundcloud/android/cast/core/CastOptionsProvider;",
    // Not a notification: the logo in the corner of the community widget on the home screen, the same picture.
    "Lcom/soundcloud/android/widget/community/ui/components/SoundCloudLogoKt;",
)

/** After every read of the field into a register, the extension answers which icon it is to be. */
private fun MutableMethod.chooseIconAfterReads(field: String): Int {
    val reads = findInstructionIndicesReversed { opcode == Opcode.SGET && fieldReference?.toString() == field }
    reads.forEach { index ->
        val register = getInstruction<OneRegisterInstruction>(index).registerA
        addInstructions(
            index + 1,
            """
                invoke-static/range { v$register .. v$register }, $BRAND_MARK->mark(I)I
                move-result v$register
            """,
        )
    }
    return reads.size
}

/**
 * The small icon of SoundCloud's notifications follows the app icon the user chose: SoundCloud's cloud for one of
 * SoundCloud's icons, the Arsound letter otherwise (`BrandMark.mark`). The choice is made when a
 * notification is built, so a new icon shows with the next one. Part of the "Arsound" patch, not shown on its own.
 */
val brandMarkPatch = bytecodePatch {
    dependsOn(brandingPatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        NOTIFICATION_CLASSES.forEach { type ->
            // The class initialiser of the player's notification only fills the field that is read below: answered
            // there, the icon would stay as it was when the class was loaded.
            val chosen = firstClassDef(type).methods.filter { it.name != "<clinit>" }
                .sumOf { it.chooseIconAfterReads(NOTIFICATION_ICON) }
            if (chosen == 0 && type != PLAYER_NOTIFICATION) throw PatchException("$type takes no notification icon any more")
        }
        val player = firstClassDef(PLAYER_NOTIFICATION).methods.sumOf { it.chooseIconAfterReads(PLAYER_NOTIFICATION_ICON) }
        if (player == 0) throw PatchException("The player's notification does not read its icon where expected")
    }
}
