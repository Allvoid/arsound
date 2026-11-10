package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.graphics.painter.Painter;
import androidx.compose.ui.res.PainterResources_androidKt;
import kotlin.jvm.functions.Function0;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Lang;

/**
 * Yandex Music, VK and YouTube Music among the apps of SoundCloud's "Import from another app" (Settings → "Import my
 * music"): SoundCloud's own import has none of them, Arsound's transfer has. Each tile looks like SoundCloud's tiles and
 * opens the transfer screen of the Arsound settings with its own service picked.
 */
@SuppressWarnings("unused")
public final class TransferTiles {
    /** One tile; stands for itself in SoundCloud's list of apps, which otherwise holds only the apps of its own import. */
    private static final class Tile {
        final String name;
        /** {@code TransferSource.id()} of the service the tile opens. */
        final String sourceId;
        final String pictureRu;
        final String pictureEn;
        final String descriptionRu;
        final String descriptionEn;

        Tile(String name, String sourceId, String pictureRu, String pictureEn, String descriptionRu, String descriptionEn) {
            this.name = name;
            this.sourceId = sourceId;
            this.pictureRu = pictureRu;
            this.pictureEn = pictureEn;
            this.descriptionRu = descriptionRu;
            this.descriptionEn = descriptionEn;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** In the order of the screen: the tiles go before SoundCloud's own apps. */
    private static final List<Tile> TILES = Arrays.asList(
            new Tile("Yandex Music", "yandex", "arsound_yandex_music_ru", "arsound_yandex_music_en",
                    "Импорт из Яндекс Музыки", "Import from Yandex Music"),
            new Tile("VK Music", "vk", "arsound_vk_music_ru", "arsound_vk_music_en",
                    "Импорт из VK Музыки", "Import from VK Music"),
            new Tile("YouTube Music", "ytmusic", "arsound_youtube_music_ru", "arsound_youtube_music_en",
                    "Импорт из YouTube Music", "Import from YouTube Music"));

    /** What drawing the tiles needs; null if something is missing, then the list has no tiles. */
    private static Drawing drawing;
    private static boolean resolved;

    private TransferTiles() {
    }

    /**
     * Injection point: replaces {@code CollectionsKt.chunked(apps, size)}, which cuts the list of apps into the rows of
     * the screen. The tiles go first, so the rows hold them as more apps.
     */
    public static List<List<Object>> rows(Iterable<Object> apps, int size) {
        List<Object> all = new ArrayList<>();
        Drawing found = drawing();
        if (found != null) all.addAll(found.tiles.keySet());
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
     * @return The app for SoundCloud to draw, or null for one of the tiles, which is drawn here.
     */
    public static Object drawTile(Object app, Object rowScope, Composer composer) {
        if (!(app instanceof Tile)) return app;
        Drawing found = drawing();
        if (found != null) found.draw((Tile) app, rowScope, composer);
        return null;
    }

    private static synchronized Drawing drawing() {
        if (resolved) return drawing;
        resolved = true;
        try {
            drawing = new Drawing();
        } catch (Exception ex) {
            Logger.printException(() -> "Transfer tiles: not available", ex);
        }
        return drawing;
    }

    /** SoundCloud's tile: the picture filling the width of its place in the row, the whole picture tappable. */
    private static final class Drawing {
        /** The tiles that have both pictures, in order, to the two pictures of each. */
        final Map<Tile, int[]> tiles = new LinkedHashMap<>();
        private final Method weight;
        private final Method clickable;
        private final Method image;
        private final Object fillWidth;

        Drawing() throws Exception {
            for (Tile tile : TILES) {
                int ru = Utils.getResourceIdentifier(ResourceType.DRAWABLE, tile.pictureRu);
                int en = Utils.getResourceIdentifier(ResourceType.DRAWABLE, tile.pictureEn);
                if (ru != 0 && en != 0) tiles.put(tile, new int[]{ru, en});
                else Logger.printInfo(() -> "Transfer tiles: no pictures for " + tile.name);
            }
            if (tiles.isEmpty()) throw new IllegalStateException("No tile pictures");
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

        void draw(Tile tile, Object rowScope, Composer composer) {
            try {
                int[] pictures = tiles.get(tile);
                if (pictures == null) return;
                Modifier modifier = (Modifier) weight.invoke(rowScope, Modifier.Companion, 1f, true);
                // The defaults of SoundCloud's own tiles (mask 0xf): enabled, no label, no role, no interaction source.
                modifier = (Modifier) clickable.invoke(null, modifier, false, null, null, null,
                        SettingsEntry.openTransfer(tile.sourceId), 0xf, null);
                Painter painter = PainterResources_androidKt.painterResource(
                        Lang.russian() ? pictures[0] : pictures[1], composer, 0);
                // As SoundCloud draws its tiles: alignment, alpha and colour filter left at their defaults (mask 0x68).
                image.invoke(null, painter, text(tile.descriptionRu, tile.descriptionEn), modifier,
                        null, fillWidth, 0f, null, composer, Painter.$stable | 0x6000, 0x68);
            } catch (Exception ex) {
                Logger.printException(() -> "Transfer tiles: could not draw " + tile.name, ex);
            }
        }
    }
}
