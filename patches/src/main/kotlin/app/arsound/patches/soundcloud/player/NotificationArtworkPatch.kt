package app.arsound.patches.soundcloud.player

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
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

/**
 * Builds the content:// address of a cover for the notification and Android Auto from an image URL and a package
 * name: the authority is "<package>.imageProvider". Both callers pass the literal "com.soundcloud.android".
 */
private val BytecodePatchContext.imageProviderUriMethod by gettingFirstMethodDeclaratively {
    name("c")
    definingClass("Lcom/soundcloud/android/image/url/uri/UriToUrlMapperKt;")
    parameterTypes("Ljava/lang/String;", "Ljava/lang/String;")
}

/** Builds the media session metadata of a track: title, artist, the cover (as a bitmap or an address), the like. */
private val BytecodePatchContext.trackMetadataMethod by gettingFirstMethodDeclaratively(
    "android.media.metadata.ALBUM_ART_URI",
    "android.media.metadata.USER_RATING",
) {
    name("apply")
    definingClass("Lcom/soundcloud/android/playback/mediasession/MetadataOperations\$trackMediaMetadata\$4;")
}

/**
 * Notification cover: On Android 13 and later SoundCloud gives the media notification the cover as an address of its
 * own image provider, named after the package. Arsound is installed under another package, so the address pointed at
 * the original SoundCloud app (or nowhere) and the notification showed a placeholder instead of the cover. With the
 * new player on, the notification gets a picture in the theme's colours instead, unless the user wants the track's
 * colours. Part of the "Arsound" patch, not shown on its own.
 */
val notificationArtworkPatch = bytecodePatch {
    compatibleWith("com.soundcloud.android"("2026.10.14-release"))

    apply {
        imageProviderUriMethod.addInstructions(
            0,
            """
                invoke-static { p1 }, Lapp/revanced/extension/soundcloud/player/NotificationArtwork;->imageProviderPackage(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
            """,
        )

        trackMetadataMethod.apply {
            val build = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.let {
                    it.definingClass == "Landroid/support/v4/media/MediaMetadataCompat\$Builder;" && it.name == "build"
                } == true
            }
            val builder = getInstruction<FiveRegisterInstruction>(build).registerC
            addInstructions(
                build,
                "invoke-static { v$builder }, Lapp/revanced/extension/soundcloud/player/NotificationArtwork;->style(Ljava/lang/Object;)V",
            )
            // p1, the track with its loaded cover, is overwritten later in the method, so its cover is kept first.
            addInstructions(
                0,
                "invoke-static { p1 }, Lapp/revanced/extension/soundcloud/player/NotificationArtwork;->remember(Ljava/lang/Object;)V",
            )
        }
    }
}
