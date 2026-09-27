package app.arsound.patches.soundcloud.player

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.patch.bytecodePatch
import app.arsound.patches.soundcloud.misc.settings.settingsPatch
import app.arsound.patches.soundcloud.power.playerPlayMethod

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/player/ListeningStats;"

/** Listening statistics: counts what was played and for how long, on this phone only. Part of the "Arsound" patch, not shown on its own. */
val listeningStatsPatch = bytecodePatch {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        playerPlayMethod.addInstruction(0, "invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->onPlaybackItem(Ljava/lang/Object;)V")
        // The player listener: playing, paused, buffering, ended.
        firstClassDef("Lcom/soundcloud/android/exoplayer/BaseExoPlayer\$exoPlayerEventListener\$1;")
            .methods.first { it.name == "onPlayerStateChanged" }
            // The method has many registers: p1 and p2 are above v15, which only a range call reaches.
            .addInstruction(0, "invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS_DESCRIPTOR->onPlayerState(ZI)V")
    }
}
