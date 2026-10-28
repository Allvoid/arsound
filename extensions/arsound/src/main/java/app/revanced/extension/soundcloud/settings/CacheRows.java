package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.text.format.Formatter;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.lyrics.LyricsRepository;
import app.revanced.extension.soundcloud.player.StreamCache;

/**
 * The rows that size, age and clear the caches: the parts of tracks streamed from the network, and the lyrics kept on
 * the phone. They live on the storage screen next to the downloads, because that is where people look for "where did
 * my space go". Used by {@link StorageScreen}.
 */
final class CacheRows {
    private CacheRows() {
    }

    /** Size and lifetime of the stream cache, and a row that clears it. */
    static void addStreamCache(SettingsUi ui, LinearLayout list) {
        long megabyte = 1024L * 1024;
        long gigabyte = 1024 * megabyte;
        list.addView(ui.createChoiceRow(text("Размер кэша", "Cache size"),
                text("Сюда SoundCloud сохраняет части треков, которые вы слушали из сети, чтобы при повторе не качать их "
                                + "снова. Скачанные треки сюда не входят. Применится после перезапуска.",
                        "SoundCloud keeps parts of the tracks you streamed here, so a replay does not download them again. "
                                + "Downloaded tracks are not in it. Applies after a restart."),
                Settings.STREAM_CACHE_SIZE, 0,
                new long[]{0, 250 * megabyte, 500 * megabyte, gigabyte, 2 * gigabyte, 5 * gigabyte, 10 * gigabyte, 20 * gigabyte},
                new String[]{text("Как в SoundCloud (120–500 МБ)", "SoundCloud default (120–500 MB)"),
                        text("250 МБ", "250 MB"), text("500 МБ", "500 MB"), text("1 ГБ", "1 GB"), text("2 ГБ", "2 GB"),
                        text("5 ГБ", "5 GB"), text("10 ГБ", "10 GB"), text("20 ГБ", "20 GB")},
                null).icon("cache").keywords(text("кэш потоков лимит объем", "stream cache limit size")));
        list.addView(ui.createChoiceRow(text("Срок хранения кэша", "Keep the cache for"),
                text("Части треков, которые вы не слушали столько дней, удаляются при запуске.",
                        "Parts of tracks you have not played for this many days are removed at start."),
                Settings.STREAM_CACHE_DAYS, 0,
                new long[]{0, 1, 7, 30, 90},
                new String[]{text("Пока хватает места", "Until the cache is full"), text("1 день", "1 day"),
                        text("7 дней", "7 days"), text("30 дней", "30 days"), text("90 дней", "90 days")},
                null).icon("clock"));
        TextView[] used = new TextView[1];
        ActionRow clearRow = ui.createActionRow(text("Очистить кэш", "Clear cache"), "", v -> {
            Settings.putBoolean(StreamCache.CLEAR_REQUESTED, true);
            used[0].setText(text("Кэш очистится при следующем запуске SoundCloud.",
                    "The cache is cleared the next time SoundCloud starts."));
        });
        clearRow.icon("trash").keywords(text("очистить кэш потоков", "clear stream cache"));
        used[0] = clearRow.description();
        used[0].setText(text("Считаю…", "Counting…"));
        ui.fillInBackground(() -> {
            long bytes = StreamCache.usedBytes();
            boolean pending = Settings.getBoolean(StreamCache.CLEAR_REQUESTED, false);
            String size = Formatter.formatShortFileSize(ui.activity, bytes);
            Utils.runOnMainThread(() -> used[0].setText(pending
                    ? text("Кэш очистится при следующем запуске SoundCloud.", "The cache is cleared the next time SoundCloud starts.")
                    : text("Сейчас занято: " + size + ". Очистится при следующем запуске.",
                    "Now used: " + size + ". Cleared at the next start.")));
        });
        list.addView(clearRow);
    }

    /** The row that clears the lyrics kept on the phone. */
    static void addLyricsCache(SettingsUi ui, LinearLayout list) {
        TextView[] used = new TextView[1];
        ActionRow clearRow = ui.createActionRow(text("Очистить кэш текстов", "Clear the lyrics cache"), "", v -> {
            used[0].setText(text("Очищаю…", "Clearing…"));
            ui.fillInBackground(() -> {
                LyricsRepository.clearStored();
                Utils.runOnMainThread(() -> used[0].setText(text("Кэш текстов пуст.", "The lyrics cache is empty.")));
            });
        });
        clearRow.icon("lyrics").keywords(text("тексты песен слова очистить", "lyrics clear"));
        used[0] = clearRow.description();
        used[0].setText(text("Считаю…", "Counting…"));
        ui.fillInBackground(() -> {
            long bytes = LyricsRepository.storedBytes();
            String size = Formatter.formatShortFileSize(ui.activity, bytes);
            Utils.runOnMainThread(() -> used[0].setText(bytes == 0
                    ? text("Кэш текстов пуст.", "The lyrics cache is empty.")
                    : text("Сейчас занято: " + size + ". Тексты найденных песен, чтобы они открывались сразу и без интернета.",
                    "Now used: " + size + ". The texts of the songs found, so they open at once and offline.")));
        });
        list.addView(clearRow);
    }
}
