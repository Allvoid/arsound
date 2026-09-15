package app.revanced.patches.soundcloud.network

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.soundcloud.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/network/NetworkPatch;"

/**
 * OkHttp is not obfuscated, and every client of the app, including its own copies made with
 * newBuilder(), is created by this method.
 */
private val BytecodePatchContext.okHttpBuildMethod by gettingFirstMethodDeclaratively {
    name("build")
    definingClass("Lokhttp3/OkHttpClient\$Builder;")
}

/** SoundCloud's own "is the network connected" check, used for offline mode, sync and retries. */
private val BytecodePatchContext.networkConnectedMethod by gettingFirstMethodDeclaratively {
    name("d")
    definingClass("Lcom/soundcloud/android/utilities/android/network/NetworkConnectionHelper;")
    returnType("Z")
}

@Suppress("unused")
val networkPatch = bytecodePatch(
    name = "Network",
    description = "Adds network options to the app HTTP clients, including a developer option that simulates a slow connection.",
) {
    dependsOn(settingsPatch)

    compatibleWith("com.soundcloud.android"("2026.09.02-release"))

    apply {
        okHttpBuildMethod.addInstructions(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->onBuild(Ljava/lang/Object;)V",
        )

        networkConnectedMethod.apply {
            // Every "return pN" of the method passes the result through the region guard first.
            implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN }
                .map { it.index }
                .reversed()
                .forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA
                    addInstructions(
                        index,
                        """
                            invoke-static { v$register }, Lapp/revanced/extension/soundcloud/network/RegionGuard;->isConnected(Z)Z
                            move-result v$register
                        """,
                    )
                }
        }
    }
}
