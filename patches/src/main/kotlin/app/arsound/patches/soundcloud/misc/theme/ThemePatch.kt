package app.arsound.patches.soundcloud.misc.theme

import app.arsound.patches.soundcloud.misc.applicationOnCreateMethod
import app.arsound.patches.soundcloud.misc.branding.brandingPatch
import app.arsound.patches.soundcloud.misc.manifest.addApplicationComponent
import app.arsound.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.extensions.wideLiteral
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.method
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

/** The dark veil over the "Your likes" bar on the home screen; two copies of that bar exist in SoundCloud. */
private val BytecodePatchContext.shortcutHeaderStaticMethod by gettingFirstMethodDeclaratively {
    name("<clinit>")
    definingClass("Lcom/soundcloud/android/sdui/components/composables/shortcuts/ShortcutActionHeaderKt;")
}
private val BytecodePatchContext.sectionsShortcutsStaticMethod by gettingFirstMethodDeclaratively {
    name("<clinit>")
    definingClass("Lcom/soundcloud/android/sections/ui/components/ShortcutsKt;")
}

private const val ASYNC_IMAGE = "Lcoil/compose/SingletonAsyncImageKt;"

/** The hearts picture of the "Your likes" bar on the home screen, in each of its two copies: drawn by AsyncImage. */
private val BytecodePatchContext.likesBarPictureMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/sdui/components/composables/shortcuts/ShortcutActionHeaderKt;")
    instructions(method { definingClass == ASYNC_IMAGE && name == "b" })
}
private val BytecodePatchContext.sectionsLikesBarPictureMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/sections/ui/components/ShortcutsKt;")
    instructions(method { definingClass == ASYNC_IMAGE && name == "b" })
}

/** The picture behind a page title ("Your likes", purchases): CollapsableAppBarBackground.Drawable(resource, 1f). */
private val BytecodePatchContext.appBarPictureMethod by gettingFirstMethodDeclaratively {
    name("a")
    definingClass("Lcom/soundcloud/android/ui/components/compose/toolbars/CollapsableAppBarBackground\$Companion;")
    returnType("Lcom/soundcloud/android/ui/components/compose/toolbars/CollapsableAppBarBackground\$Drawable;")
}

/**
 * The Downloads page of the new design draws its own picture and the dark veil over it with two plain Image calls
 * (ln/e, the lambda of the page header), not with CollapsableAppBarBackground.
 */
private val BytecodePatchContext.downloadsHeaderMethod by gettingFirstMethodDeclaratively {
    name("invoke")
    definingClass("Lln/e;")
    parameterTypes("Ljava/lang/Object;", "Ljava/lang/Object;")
}

/** Builds the rows of the Library tab ("Your likes", "Playlists", ...), each without a leading icon. */
private val BytecodePatchContext.libraryHeaderItemConstructorMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lcom/soundcloud/android/features/library/LibraryHeaderItem;")
    parameterTypes("Landroid/content/Context;", "Landroid/util/AttributeSet;")
}

/**
 * Builds the state of the Downloads row again when the Library list is bound: {@code LibraryLinksViewHolder.a(Z)},
 * the only method of the holder that returns a row state. The row view is not at hand there.
 */
private val BytecodePatchContext.libraryDownloadsStateMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/soundcloud/android/features/library/LibraryLinksViewHolder;")
    returnType("Lcom/soundcloud/android/ui/components/actionlists/ActionListItem\$ViewState;")
    parameterTypes("Z")
}

/** SoundCloud's veil colour: 70 % black. */
private const val SHORTCUT_SCRIM = 0xb3000000L

/**
 * The files of the themes (description, fonts) as app assets, the resources each theme brings, and a start screen per
 * theme. The writers run in this order; the start screen tints the drawing letter that the branding patch writes.
 */
private val themeResourcesPatch = resourcePatch {
    dependsOn(brandingPatch)

    apply {
        val json = themeResource("themes.json").use { it.readBytes() }
        writeThemeAssets(json)
        val themes = readThemes(String(json))
        writeThemeResources(themes)
        writeSplashScreens(themes)
        // Wakes the app right after an update, so the theme's resource table is built then and not at the first start.
        document("AndroidManifest.xml").use { document ->
            val receiver = document.addApplicationComponent(
                "receiver",
                "android:name" to "app.revanced.extension.soundcloud.theme.ThemeUpdateReceiver",
                "android:exported" to "false",
            )
            val filter = document.createElement("intent-filter")
            filter.appendChild(document.createElement("action").apply {
                setAttribute("android:name", "android.intent.action.MY_PACKAGE_REPLACED")
            })
            receiver.appendChild(filter)
        }
    }
}

/** Arsound themes: Applies the colour theme chosen in Settings → Arsound → Appearance. Part of the "Arsound" patch, not shown on its own. */
val themePatch = bytecodePatch {
    dependsOn(themeResourcesPatch, wallpaperSurfacesPatch, cardSurfacesPatch, readMoreLinkPatch)
    compatibleWith("com.soundcloud.android")

    apply {
        applicationOnCreateMethod.addInstruction(
            0,
            "invoke-static { p0 }, Lapp/revanced/extension/soundcloud/theme/ArsoundTheme;" +
                "->onApplicationCreate(Landroid/app/Application;)V",
        )
        // Each Library row asks the theme for a leading icon. The rows are built in LibraryHeaderItem; the Downloads
        // row is built again when the list is bound (LibraryLinksViewHolder.a).
        val rows = libraryHeaderItemConstructorMethod.addLibraryRowIcons(RowIconSource.ByView) +
            libraryDownloadsStateMethod.addLibraryRowIcons(RowIconSource.ByRowName("downloads"))
        if (rows < 9) error("Library rows not found ($rows)")
        // The picture behind a page title takes its opacity from HeaderArtwork (zero hides it): the constant 1f
        // is replaced by the call, its result goes into the same register.
        appBarPictureMethod.apply {
            val index = indexOfFirstInstructionOrThrow { opcode == Opcode.CONST_HIGH16 }
            val register = getInstruction<OneRegisterInstruction>(index).registerA
            // The resource id is the last parameter, an int: the method's last register.
            val resource = implementation!!.registerCount - 1
            replaceInstruction(
                index,
                "invoke-static { v$resource }, Lapp/revanced/extension/soundcloud/theme/HeaderArtwork;->alpha(I)F",
            )
            addInstruction(index + 1, "move-result v$register")
        }
        // The picture of the Downloads page and the veil over it take their opacity from HeaderArtwork too: both
        // Image calls get the alpha as an argument (the "default" bit of the alpha is taken off the mask).
        downloadsHeaderMethod.apply {
            val bannerIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET && (this as com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                    .reference.toString().endsWith("R\$drawable;->downloads_banner:I")
            }
            val isImage: com.android.tools.smali.dexlib2.iface.instruction.Instruction.() -> Boolean = {
                opcode == Opcode.INVOKE_STATIC_RANGE &&
                    (this as com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                        .reference.toString().startsWith("Landroidx/compose/foundation/ImageKt;->Image(")
            }
            val bannerImage = indexOfFirstInstructionOrThrow(bannerIndex, isImage)
            val veilImage = indexOfFirstInstructionOrThrow(bannerImage + 1, isImage)
            // Later call first, so the index of the earlier one stays right.
            for (call in listOf(veilImage, bannerImage)) {
                val start = getInstruction<RegisterRangeInstruction>(call).startRegister
                val alpha = start + 5
                val mask = start + 9
                addInstructions(
                    call,
                    """
                        invoke-static { }, Lapp/revanced/extension/soundcloud/theme/HeaderArtwork;->downloadsAlpha()F
                        move-result v$alpha
                        and-int/lit8 v$mask, v$mask, -0x21
                    """,
                )
            }
        }
        // The hearts of the "Your likes" bar come from the server as a picture: they get the theme's colour filter
        // where they are drawn. AsyncImage(model, description, modifier, alignment, scale, colorFilter, composer,
        // changed, changed1, defaults) is called with a register range: the filter is its sixth register, and the
        // last one is Kotlin's mask of the parameters left to their defaults, where SoundCloud marks the filter (bit 8:
        // the mask counts Coil's parameters as written, before the shrinking took some out).
        // That bit is taken off, or the filter passed would be ignored; the default filter is none anyway.
        for (method in listOf(likesBarPictureMethod, sectionsLikesBarPictureMethod)) {
            method.apply {
                val index = indexOfFirstInstructionOrThrow {
                    opcode == Opcode.INVOKE_STATIC_RANGE && methodReference?.definingClass == ASYNC_IMAGE &&
                        methodReference?.name == "b"
                }
                val start = getInstruction<RegisterRangeInstruction>(index).startRegister
                val filter = start + 5
                val defaults = start + 9
                addInstructions(
                    index,
                    """
                        invoke-static/range { v$filter .. v$filter }, Lapp/revanced/extension/soundcloud/theme/HomeLikesHeart;->filter(Landroidx/compose/ui/graphics/ColorFilter;)Landroidx/compose/ui/graphics/ColorFilter;
                        move-result-object v$filter
                        invoke-static/range { v$defaults .. v$defaults }, Lapp/revanced/extension/soundcloud/theme/HomeLikesHeart;->useFilter(I)I
                        move-result v$defaults
                    """,
                )
            }
        }
        // The theme may lighten the veil over the "Your likes" bar, so the bar shows the theme's colours.
        for (method in listOf(shortcutHeaderStaticMethod, sectionsShortcutsStaticMethod)) {
            method.apply {
                val index = indexOfFirstInstructionOrThrow {
                    opcode == Opcode.CONST_WIDE && wideLiteral == SHORTCUT_SCRIM
                }
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register, v${register + 1} }, Lapp/revanced/extension/soundcloud/theme/ArsoundTheme;->shortcutScrim(J)J
                        move-result-wide v$register
                    """,
                )
            }
        }
    }
}
