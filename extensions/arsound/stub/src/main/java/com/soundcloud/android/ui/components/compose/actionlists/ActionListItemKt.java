package com.soundcloud.android.ui.components.compose.actionlists;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import com.soundcloud.android.ui.components.labels.icons.DownloadIcon;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function4;

/**
 * The action list row composable used by the native SoundCloud settings screen.
 */
public final class ActionListItemKt {
    /**
     * @param afterTitle  Drawn right after the title: (RowScope, the text colour as a boxed Color, Composer, changed).
     * @param padding     The horizontal padding, in dp.
     * @param defaults    Bitmask of the parameters left at their default value, from 0x4 (modifier) on.
     */
    public static void a(
            String title,
            Function0<?> onClick,
            Modifier modifier,
            boolean z2,
            boolean z4,
            Integer iconStart,
            Integer iconEnd,
            Function4<?, ?, ?, ?, ?> afterTitle,
            DownloadIcon.Step step,
            ActionListSelectableState selectableState,
            float padding,
            Composer composer,
            int changed,
            int defaults
    ) {
    }
}
