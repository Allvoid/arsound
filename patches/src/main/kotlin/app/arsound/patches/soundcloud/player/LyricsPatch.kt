package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.SOUNDCLOUD_VERSION
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.util.ResourceGroup
import app.arsound.util.copyResources
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.returnType

private const val HOOKS_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/lyrics/LyricsHooks;"
private const val CLOCK_DESCRIPTOR = "Lapp/revanced/extension/soundcloud/lyrics/LyricsClock;"

/**
 * Puts new metadata on the media session. The queue the app closed with is restored through it right after the service
 * starts, and its state goes to the session without passing the playback state method: this is where the clock learns of the
 * service in time.
 */
private val BytecodePatchContext.mediaServiceMetadataMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/playback/players/MediaService;")
    name("o")
    returnType("V")
    parameterTypes("Landroid/support/v4/media/MediaMetadataCompat;")
}

/**
 * The lyrics button and its two icons. The button goes last in the column of round buttons at the top right of the
 * full-screen player (chevron, follow, QR code, cast), so it is right under the follow button when the other two
 * are not there, and under the cast button when that one is. It is hidden until the lyrics code shows it; the
 * "new player" has the same button in its own copy of the layout.
 */
private val lyricsResourcePatch = resourcePatch {
    apply {
        copyResources(
            "soundcloud/player",
            ResourceGroup("drawable", "arsound_lyrics.xml", "arsound_lyrics_on.xml"),
        )
        document("res/layout/player_vertical_top_bar.xml").use { document ->
            document.documentElement.appendChild(
                document.createElement("ImageButton").apply {
                    setAttribute("android:id", "@+id/arsound_lyrics_toggle")
                    setAttribute("android:visibility", "gone")
                    setAttribute("android:layout_marginTop", "@dimen/top_margin_icon")
                    setAttribute("app:layout_goneMarginTop", "@dimen/top_margin_icon")
                    setAttribute("app:layout_constraintEnd_toEndOf", "parent")
                    setAttribute("app:layout_constraintStart_toStartOf", "parent")
                    setAttribute("app:layout_constraintTop_toBottomOf", "@id/media_route_button")
                    setAttribute("app:srcCompat", "@drawable/arsound_lyrics")
                    setAttribute("app:tint", "@color/black")
                    setAttribute("style", "@style/PlayerTopActionIcon")
                },
            )
        }
    }
}

/**
 * Lyrics: A button in the full-screen player that shows the words of the playing song over the blurred cover, found
 * on LRCLIB. Synced lyrics light up line by line and jump the song to a tapped line. Part of the "Arsound" patch,
 * not shown on its own.
 */
val lyricsPatch = bytecodePatch {
    dependsOn(settingsPatch, lyricsResourcePatch)

    compatibleWith("com.soundcloud.android"(SOUNDCLOUD_VERSION))

    apply {
        // The range form is required: these methods use dozens of registers, and a plain
        // invoke-static only reaches the first sixteen of them.
        bindPlayerPageMethod.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p2 }, $HOOKS_DESCRIPTOR->onBind(Ljava/lang/Object;Ljava/lang/Object;)V
            """,
        )
        slidePlayerPageMethod.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p2 }, $HOOKS_DESCRIPTOR->onSlide(Landroid/view/View;F)V
            """,
        )
        // The state as SoundCloud made it, before any custom action is added to it: the clock reads the position and
        // the track from it, and seeks through the media session of this service.
        mediaServicePlaybackStateMethod.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p1 }, $CLOCK_DESCRIPTOR->onState(Ljava/lang/Object;Ljava/lang/Object;)V
            """,
        )
        // The service itself, for a state that was restored at the start and never passed the hook above.
        mediaServiceMetadataMethod.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p0 }, $CLOCK_DESCRIPTOR->onService(Ljava/lang/Object;)V
            """,
        )
    }
}
