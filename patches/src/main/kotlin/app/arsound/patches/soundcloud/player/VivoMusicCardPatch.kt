package app.arsound.patches.soundcloud.player

import app.arsound.util.getNode
import app.revanced.patcher.patch.resourcePatch

/**
 * vivo control centre player: The music player of vivo phones (com.vivo.musicwidgetmix) shows only the apps of its
 * own list. Arsound gives a command for a computer that grants it WRITE_SECURE_SETTINGS and wakes the receiver, which
 * adds Arsound to that list. Part of the "Arsound" patch, not shown on its own.
 */
val vivoMusicCardPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getNode("manifest")
            // Only adb can grant it; without it Arsound only reads the list.
            manifest.appendChild(
                document.createElement("uses-permission").apply {
                    setAttribute("android:name", "android.permission.WRITE_SECURE_SETTINGS")
                },
            )
            // Lets Arsound see whether the vivo player is on the phone.
            manifest.appendChild(
                document.createElement("queries").apply {
                    appendChild(
                        document.createElement("package").apply {
                            setAttribute("android:name", "com.vivo.musicwidgetmix")
                        },
                    )
                },
            )
            // Only a sender holding WRITE_SECURE_SETTINGS itself (the adb shell) may wake it.
            document.getNode("application").appendChild(
                document.createElement("receiver").apply {
                    setAttribute("android:name", "app.revanced.extension.soundcloud.player.VivoMusicCardReceiver")
                    setAttribute("android:exported", "true")
                    setAttribute("android:permission", "android.permission.WRITE_SECURE_SETTINGS")
                },
            )
        }
    }
}
