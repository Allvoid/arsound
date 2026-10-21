package app.revanced.extension.soundcloud.settings;

import android.content.Context;
import android.content.Intent;

import androidx.compose.foundation.layout.SpacerKt;
import androidx.compose.material.IconKt;
import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.graphics.painter.Painter;
import androidx.compose.ui.res.PainterResources_androidKt;
import androidx.compose.ui.res.PrimitiveResources_androidKt;
import androidx.compose.ui.res.StringResources_androidKt;
import com.soundcloud.android.ui.components.compose.actionlists.ActionListItemKt;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function4;

import java.lang.reflect.Method;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;

@SuppressWarnings("unused")
public final class SettingsEntry {
    /**
     * Bitmask of the parameters left at their default value.
     * SoundCloud passes 1980 for its own rows with a trailing chevron; bit 0x20 (the start icon)
     * is cleared here, otherwise the passed icon is replaced by the default and never shows.
     */
    private static final int DEFAULT_PARAMETERS_MASK = 1980 & ~0x20;
    /** For the Arsound row: the content after the title (bit 0x80) is passed. */
    private static final int ENTRY_PARAMETERS_MASK = 1980 & ~0x80;

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
    private static Integer iconStart() {
        int icon = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_icon");
        return icon == 0 ? null : icon;
    }

    private static final Function0<Unit> IMPORT_FILES = () -> {
        app.revanced.extension.soundcloud.local.ImportActivity.start(Utils.getContext(), null);
        return Unit.INSTANCE;
    };

    /**
     * Injection point. Called from the Compose code of SoundCloud's "Import my music" screen,
     * below "Manage imported likes". Imports audio files from the phone.
     */
    public static void addImportEntry(Composer composer) {
        ActionListItemKt.a(
                "ru".equals(java.util.Locale.getDefault().getLanguage())
                        ? "Импорт файлов с телефона" : "Import files from this phone",
                IMPORT_FILES,
                null,
                false,
                false,
                iconStart(),
                Utils.getResourceIdentifier(ResourceType.DRAWABLE, "ic_actions_chevron_right"),
                null,
                null,
                null,
                0,
                composer,
                0,
                DEFAULT_PARAMETERS_MASK
        );
    }

    /**
     * Injection point: the "Arsound" row at the top of SoundCloud's settings. The letter stands right after the name
     * (the row's icon after the title, with the app name as its description), the chevron stays at the end.
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
                letterAfterTitle(),
                null,
                null,
                0,
                composer,
                0,
                ENTRY_PARAMETERS_MASK
        );
    }

    private static Function4<Object, Object, Composer, Integer, Unit> letterAfterTitle;

    /**
     * The letter right after the row title, as SoundCloud draws an icon there: a spacing_m gap, then the picture
     * in its own colours, with the app name as its description. Null if something it needs is missing, so the
     * row is drawn without it rather than half of it.
     */
    private static Function4<Object, Object, Composer, Integer, Unit> letterAfterTitle() {
        if (letterAfterTitle != null) return letterAfterTitle;
        Integer letter = iconStart();
        int appName = Utils.getResourceIdentifier(ResourceType.STRING, "app_name");
        int gap = Utils.getResourceIdentifier(ResourceType.DIMEN, "spacing_m");
        if (letter == null || appName == 0 || gap == 0) return null;
        try {
            // Kotlin gave these names a hash suffix with a dash, which Java code cannot call by name.
            Method width = Class.forName("androidx.compose.foundation.layout.SizeKt")
                    .getMethod("width-3ABfNKs", Modifier.class, float.class);
            Class<?> colorCompanion = Class.forName("androidx.compose.ui.graphics.Color$Companion");
            long unspecified = (long) colorCompanion.getMethod("getUnspecified-0d7_KjU").invoke(
                    Class.forName("androidx.compose.ui.graphics.Color").getField("Companion").get(null));
            letterAfterTitle = (row, color, composer, changed) -> {
                try {
                    float spacing = PrimitiveResources_androidKt.dimensionResource(gap, composer, 0);
                    SpacerKt.Spacer((Modifier) width.invoke(null, Modifier.Companion, spacing), composer, 0);
                } catch (Exception ex) {
                    Logger.printException(() -> "Arsound row: no gap before the letter", ex);
                }
                IconKt.a(PainterResources_androidKt.painterResource(letter, composer, 0),
                        StringResources_androidKt.stringResource(appName, composer, 0),
                        null, unspecified, composer, Painter.$stable, 4);
                return Unit.INSTANCE;
            };
        } catch (Exception ex) {
            Logger.printException(() -> "Arsound row: the letter after the title is not available", ex);
        }
        return letterAfterTitle;
    }
}
