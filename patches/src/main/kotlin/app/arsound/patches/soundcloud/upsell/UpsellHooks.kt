package app.arsound.patches.soundcloud.upsell

/** The extension class that answers for the paywall, the marketing popups, the banners and the bottom bar tabs. */
internal const val HIDE_PAYWALL_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/upsell/HidePaywallPatch;"

/** The extension class of the explanation shown on the Upgrade tab in place of the shop. */
internal const val UPGRADE_TAB_DESCRIPTOR =
    "Lapp/revanced/extension/soundcloud/upsell/UpgradeTabPage;"

/** Asks whether marketing popups and server-driven offers are hidden; the guards of two patches use it. */
internal const val HIDE_IN_APP_MESSAGES_CALL =
    "invoke-static { }, $HIDE_PAYWALL_DESCRIPTOR->hideInAppMessages()Z"
