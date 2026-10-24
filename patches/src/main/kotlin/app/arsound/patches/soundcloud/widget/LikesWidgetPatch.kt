package app.arsound.patches.soundcloud.widget

import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/widget/WidgetImages;"

/** A cover of the "Your likes" widget. */
private val BytecodePatchContext.likesWidgetTrackImageMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/widget/likedtracks/ui/components/TrackImageKt;")
}

/** The user's avatar in the top row of the "Your likes" widget. */
private val BytecodePatchContext.likesWidgetUserAvatarMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/widget/likedtracks/ui/components/UserAvatarKt;")
}

/** The file of a cover or avatar for the "Your likes" widget, from SoundCloud's ImageFileFinder. */
private val BytecodePatchContext.likesWidgetImageFileMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/widget/likedtracks/domain/LikedTracksWidgetRepo;")
    returnType("Ljava/lang/String;")
}

/**
 * "Your likes" widget: Shows the covers instead of an error: a cached error page is not taken for a picture, missing
 * covers are downloaded, and a picture that still cannot be read is a grey square.
 * Part of the "Arsound" patch, not shown on its own.
 */
val likesWidgetPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.10.14-release"))

    apply {
        // The cover files come from WidgetImages.find instead of ImageFileFinder.b(context, url): the finder object
        // stays the first argument, so the call keeps its registers.
        likesWidgetImageFileMethod.apply {
            val index = instructions.indexOfFirst { instruction ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    instruction.methodReference?.let {
                        it.definingClass == "Lcom/soundcloud/android/playback/image/notification/ImageFileFinder;" &&
                            it.returnType == "Ljava/io/File;"
                    } == true
            }
            if (index < 0) error("ImageFileFinder call not found")
            val call = getInstruction<FiveRegisterInstruction>(index)
            replaceInstruction(
                index,
                "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE} }, " +
                    "$EXTENSION_CLASS_DESCRIPTOR->find(Ljava/lang/Object;Landroid/content/Context;Ljava/lang/String;)Ljava/io/File;",
            )
        }

        // Both pictures are decoded by BitmapFactory.decodeFile and used without a null check; the call is
        // replaced by one that never returns null. Same signature, so the move-result after it stays.
        for (method in listOf(likesWidgetTrackImageMethod, likesWidgetUserAvatarMethod)) {
            method.apply {
                val indexes = instructions.withIndex().filter { (_, instruction) ->
                    instruction.opcode == Opcode.INVOKE_STATIC &&
                        instruction.methodReference?.let {
                            it.definingClass == "Landroid/graphics/BitmapFactory;" && it.name == "decodeFile"
                        } == true
                }.map { it.index }
                if (indexes.isEmpty()) error("decodeFile not found in ${method.definingClass}")
                indexes.forEach { index ->
                    val path = getInstruction<FiveRegisterInstruction>(index).registerC
                    replaceInstruction(
                        index,
                        "invoke-static { v$path }, $EXTENSION_CLASS_DESCRIPTOR->decode(Ljava/lang/String;)Landroid/graphics/Bitmap;",
                    )
                }
            }
        }
    }
}
