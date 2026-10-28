package app.arsound.patches.soundcloud.player

import app.arsound.patches.soundcloud.misc.manifest.addApplicationComponent
import app.arsound.patches.soundcloud.misc.manifest.appendElement
import app.revanced.patcher.patch.resourcePatch

/**
 * vivo control centre player: The music player of vivo phones (com.vivo.musicwidgetmix) shows only the apps of its
 * own list. Arsound gives a command for a computer that grants it WRITE_SECURE_SETTINGS and wakes the receiver, which
 * adds Arsound to that list. Part of the "Arsound" patch, not shown on its own.
 */
val vivoMusicCardPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { document ->
            // Only adb can grant it; without it Arsound only reads the list.
            document.appendElement(
                "manifest",
                "uses-permission",
                "android:name" to "android.permission.WRITE_SECURE_SETTINGS",
            )
            // Lets Arsound see whether the vivo player is on the phone.
            document.appendElement("manifest", "queries").appendChild(
                document.createElement("package").apply {
                    setAttribute("android:name", "com.vivo.musicwidgetmix")
                },
            )
            // Only a sender holding WRITE_SECURE_SETTINGS itself (the adb shell) may wake it.
            document.addApplicationComponent(
                "receiver",
                "android:name" to "app.revanced.extension.soundcloud.player.VivoMusicCardReceiver",
                "android:exported" to "true",
                "android:permission" to "android.permission.WRITE_SECURE_SETTINGS",
            )
        }
    }
}
