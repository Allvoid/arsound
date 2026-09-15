package app.revanced.patches.soundcloud.offline

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.soundcloud.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/offline/OfflineFirstPatch;"

private const val FUNCTION_CLASS =
    "Lcom/soundcloud/android/playlists/DataSourceProvider\$fetchAndSyncPlaylistOrFallbackToLocal\$1;"

/**
 * Runs once the stored playlist with its tracks is found. For playlists of other users it
 * requests the server copy and waits for it before the screen gets any data.
 */
private val BytecodePatchContext.fetchAndSyncPlaylistMethod by gettingFirstMethodDeclaratively {
    name("apply")
    definingClass(FUNCTION_CLASS)
}

@Suppress("unused")
val offlineFirstPatch = bytecodePatch(
    name = "Offline first playlists",
    description = "Shows stored playlists and albums immediately and refreshes them in the background.",
) {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        fetchAndSyncPlaylistMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->isEnabled()Z
                move-result v0
                if-eqz v0, :original
                iget-object v0, p0, $FUNCTION_CLASS->a:Lcom/soundcloud/android/playlists/DataSourceProvider;
                iget-object v0, v0, Lcom/soundcloud/android/playlists/DataSourceProvider;->c:Lcom/soundcloud/android/foundation/domain/playlists/PlaylistWithTracksRepository;
                iget-object v1, p0, $FUNCTION_CLASS->c:Lcom/soundcloud/android/foundation/domain/Urn;
                invoke-static { v0, v1 }, $EXTENSION_CLASS_DESCRIPTOR->syncInBackground(Ljava/lang/Object;Ljava/lang/Object;)V
                iget-object v0, p0, $FUNCTION_CLASS->d:Lcom/soundcloud/android/foundation/domain/repository/SingleItemResponse;
                invoke-static { v0 }, Lio/reactivex/rxjava3/core/Single;->n(Ljava/lang/Object;)Lio/reactivex/rxjava3/internal/operators/single/SingleJust;
                move-result-object v0
                return-object v0
            """,
            ExternalLabel("original", fetchAndSyncPlaylistMethod.getInstruction(0)),
        )
    }
}
