package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.graphics.painter.Painter;
import androidx.compose.ui.res.PainterResources_androidKt;
import kotlin.jvm.functions.Function0;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Lang;

/**
 * "Yandex Music" among the apps of SoundCloud's "Import from another app" (Settings → "Import my music"): SoundCloud's
 * own import has no Yandex Music, Arsound's transfer has. The tile looks like SoundCloud's tiles and opens the transfer
 * screen of the Arsound settings.
 */
@SuppressWarnings("unused")
public final class YandexImportTile {
    /** Stands for the tile in SoundCloud's list of apps, which otherwise holds only the apps of its own import. */
    private static final Object TILE = new Object() {
        @Override
        public String toString() {
            return "Yandex Music";
        }
    };

    /** What drawing the tile needs; null if something is missing, then the list has no tile. */
    private static Drawing drawing;
    private static boolean resolved;

    private YandexImportTile() {
    }

    /**
     * Injection point: replaces {@code CollectionsKt.chunked(apps, size)}, which cuts the list of apps into the rows of
     * the screen. The tile goes first, so the rows hold it as one more app.
     */
    public static List<List<Object>> rows(Iterable<Object> apps, int size) {
        List<Object> all = new ArrayList<>();
        if (drawing() != null) all.add(TILE);
        for (Object app : apps) all.add(app);
        int perRow = Math.max(1, size);
        List<List<Object>> rows = new ArrayList<>();
        for (int start = 0; start < all.size(); start += perRow) {
            rows.add(new ArrayList<>(all.subList(start, Math.min(all.size(), start + perRow))));
        }
        return rows;
    }

    /**
     * Injection point: an app of a row, about to be drawn by SoundCloud.
     *
     * @param rowScope The {@code RowScope} of the row.
     * @return The app for SoundCloud to draw, or null for the tile, which is drawn here.
     */
    public static Object drawTile(Object app, Object rowScope, Composer composer) {
        if (app != TILE) return app;
        Drawing found = drawing();
        if (found != null) found.draw(rowScope, composer);
        return null;
    }

    private static synchronized Drawing drawing() {
        if (resolved) return drawing;
        resolved = true;
        try {
            drawing = new Drawing();
        } catch (Exception ex) {
            Logger.printException(() -> "Yandex Music tile: not available", ex);
        }
        return drawing;
    }

    /** SoundCloud's tile: the picture filling the width of its place in the row, the whole picture tappable. */
    private static final class Drawing {
        private final Method weight;
        private final Method clickable;
        private final Method image;
        private final Object fillWidth;
        private final int pictureRu;
        private final int pictureEn;

        Drawing() throws Exception {
            pictureRu = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_yandex_music_ru");
            pictureEn = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_yandex_music_en");
            if (pictureRu == 0 || pictureEn == 0) throw new IllegalStateException("No tile pictures");
            weight = Class.forName("androidx.compose.foundation.layout.RowScope")
                    .getMethod("weight", Modifier.class, float.class, boolean.class);
            // Kotlin gave these names a hash suffix with a dash, which Java code cannot call by name.
            clickable = Class.forName("androidx.compose.foundation.ClickableKt").getMethod("clickable-oSLSa3U$default",
                    Modifier.class, boolean.class, String.class, Class.forName("androidx.compose.ui.semantics.Role"),
                    Class.forName("androidx.compose.foundation.interaction.MutableInteractionSource"), Function0.class,
                    int.class, Object.class);
            Class<?> contentScale = Class.forName("androidx.compose.ui.layout.ContentScale");
            image = Class.forName("androidx.compose.foundation.ImageKt").getMethod("Image", Painter.class, String.class,
                    Modifier.class, Class.forName("androidx.compose.ui.Alignment"), contentScale, float.class,
                    Class.forName("androidx.compose.ui.graphics.ColorFilter"), Composer.class, int.class, int.class);
            Object companion = contentScale.getField("Companion").get(null);
            fillWidth = companion.getClass().getMethod("getFillWidth").invoke(companion);
        }

        void draw(Object rowScope, Composer composer) {
            try {
                Modifier modifier = (Modifier) weight.invoke(rowScope, Modifier.Companion, 1f, true);
                // The defaults of SoundCloud's own tiles (mask 0xf): enabled, no label, no role, no interaction source.
                modifier = (Modifier) clickable.invoke(null, modifier, false, null, null, null,
                        SettingsEntry.OPEN_TRANSFER, 0xf, null);
                Painter painter = PainterResources_androidKt.painterResource(
                        Lang.russian() ? pictureRu : pictureEn, composer, 0);
                // As SoundCloud draws its tiles: alignment, alpha and colour filter left at their defaults (mask 0x68).
                image.invoke(null, painter, text("Импорт из Яндекс Музыки", "Import from Yandex Music"), modifier,
                        null, fillWidth, 0f, null, composer, Painter.$stable | 0x6000, 0x68);
            } catch (Exception ex) {
                Logger.printException(() -> "Yandex Music tile: could not draw", ex);
            }
        }
    }
}
