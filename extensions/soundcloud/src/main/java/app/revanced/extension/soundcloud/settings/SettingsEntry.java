package app.revanced.extension.soundcloud.settings;

import android.content.Context;
import android.content.Intent;

import androidx.compose.runtime.Composer;
import com.soundcloud.android.ui.components.compose.actionlists.ActionListItemKt;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;

@SuppressWarnings("unused")
public final class SettingsEntry {
    /**
     * Bitmask of the parameters left at their default value.
     * Same value SoundCloud passes for its own rows with a trailing chevron.
     */
    private static final int DEFAULT_PARAMETERS_MASK = 4028;

    private static final Function0<Unit> OPEN_SETTINGS = () -> {
        try {
            Context context = Utils.getContext();
            Intent intent = new Intent(context, ReVancedSettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to open ReVanced settings", ex);
        }
        return Unit.INSTANCE;
    };

    /**
     * Injection point. Called from the Compose code of the SoundCloud settings screen.
     * Adds an "Arsound" row using the same composable as the native rows.
     */
    public static void addEntry(Composer composer) {
        ActionListItemKt.a(
                "Arsound",
                OPEN_SETTINGS,
                null,
                false,
                false,
                null,
                Utils.getResourceIdentifier(ResourceType.DRAWABLE, "ic_actions_chevron_right"),
                null,
                null,
                false,
                null,
                null,
                composer,
                0,
                0,
                DEFAULT_PARAMETERS_MASK
        );
    }
}
