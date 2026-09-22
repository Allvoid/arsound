package app.revanced.extension.soundcloud.local;

import android.widget.Toast;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.draw.AlphaKt;
import com.soundcloud.android.ui.components.compose.listviews.CellIcon;
import com.soundcloud.android.ui.components.compose.listviews.track.CellSmallTrackEndContentRowScope;

import java.lang.reflect.Field;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/**
 * Marks in the track cells of a playlist screen.
 * <ul>
 *     <li>Imported tracks (files on this phone) get a note icon next to the "more" button.</li>
 *     <li>Tracks deleted on SoundCloud and kept by {@link RemovedTracks} are greyed out; tapping one says
 *     why it does not play.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class TrackCellMarks {
    /** Key of the Compose group around the icon, so the cell's slots stay in place whether it is shown or not. */
    private static final int GROUP_KEY = 0x41727331;
    private static final float DELETED_ALPHA = 0.4f;

    private static final boolean RUSSIAN = "ru".equals(Locale.getDefault().getLanguage());

    private static volatile CellIcon importedIcon;

    private static final Function0<Unit> IMPORTED_TAP = () -> {
        Toast.makeText(Utils.getContext(), RUSSIAN
                ? "Импортированный трек: играет из файла на этом телефоне"
                : "Imported track: plays from a file on this phone", Toast.LENGTH_SHORT).show();
        return Unit.INSTANCE;
    };

    private TrackCellMarks() {
    }

    private static String urnOf(Object trackItem) {
        try {
            return String.valueOf(trackItem.getClass().getMethod("getUrn").invoke(trackItem));
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isImported(Object trackItem) {
        try {
            Object urn = trackItem.getClass().getMethod("getUrn").invoke(trackItem);
            return urn != null && urn.getClass().getName().endsWith(".LocalTrackUrn");
        } catch (Exception ex) {
            return false;
        }
    }

    private static CellIcon importedIcon() {
        CellIcon icon = importedIcon;
        if (icon == null) {
            int drawable = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "ic_actions_musical_note");
            int description = Utils.getResourceIdentifier(ResourceType.STRING, "arsound_imported_track");
            if (drawable == 0 || description == 0) return null;
            // The ordinal only tells Compose when the icon changed; it is past SoundCloud's own icons.
            importedIcon = icon = new CellIcon("ARSOUND_IMPORTED", 100, drawable, description);
        }
        return icon;
    }

    /**
     * Injection point. Called in the end row of a playlist track cell, right before the "more" button.
     */
    public static void addImportedIcon(Object scope, Object trackItem, Composer composer) {
        composer.r(GROUP_KEY);
        try {
            CellIcon icon = isImported(trackItem) ? importedIcon() : null;
            if (icon != null) {
                ((CellSmallTrackEndContentRowScope) scope).a(icon, IMPORTED_TAP, null, composer, 6, 4);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add the imported track icon", ex);
        } finally {
            composer.o();
        }
    }

    /**
     * Injection point. Called with the modifier of a playlist track cell.
     *
     * @return The modifier, faded if the track was deleted on SoundCloud.
     */
    public static Object cellModifier(Object modifier, Object trackItem) {
        try {
            String urn = urnOf(trackItem);
            if (urn == null) return modifier;
            RemovedTracks.rememberTitle(urn, (String) trackItem.getClass().getMethod("getTitle").invoke(trackItem),
                    (String) trackItem.getClass().getMethod("getCreatorName").invoke(trackItem));
            if (RemovedTracks.isDeleted(urn)) return AlphaKt.alpha((Modifier) modifier, DELETED_ALPHA);
        } catch (Exception ex) {
            Logger.printException(() -> "cellModifier failure", ex);
        }
        return modifier;
    }

    /**
     * Injection point. Called when a playlist track cell is tapped.
     *
     * @param action The tap action: its int field is 0 for "play", its item field is the tapped track.
     * @return True if the tap was handled here and must not start playback.
     */
    public static boolean onTap(Object action) {
        try {
            int kind = -1;
            Object item = null;
            for (Field field : action.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                if (field.getType() == int.class) kind = field.getInt(action);
                else if (field.getType().getName().endsWith("PlaylistDetailItem$PlaylistDetailTrackItem")) item = field.get(action);
            }
            if (kind != 0 || item == null) return false;

            Object trackItem = null;
            for (Field field : item.getClass().getDeclaredFields()) {
                if (field.getType().getName().endsWith(".TrackItem")) {
                    field.setAccessible(true);
                    trackItem = field.get(item);
                }
            }
            String urn = trackItem == null ? null : urnOf(trackItem);
            if (urn == null || !RemovedTracks.isDeleted(urn)) return false;

            Toast.makeText(Utils.getContext(), RUSSIAN
                    ? "Трек удалён из SoundCloud. Скачан он не был, поэтому послушать его нельзя — "
                    + "он остался в плейлисте как запись о том, что здесь был"
                    : "This track was deleted from SoundCloud and was not downloaded, so it cannot be played. "
                    + "It stays in the playlist as a record", Toast.LENGTH_LONG).show();
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "onTap failure", ex);
            return false;
        }
    }
}
