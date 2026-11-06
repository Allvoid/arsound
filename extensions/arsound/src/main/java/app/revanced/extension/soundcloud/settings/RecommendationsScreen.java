package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;

import app.revanced.extension.soundcloud.recommendations.TrackDislikes;
import app.revanced.extension.soundcloud.recommendations.WaveMemory;
import app.revanced.extension.soundcloud.recommendations.WaveSource;

/** My Wave, hiding duplicates, the "Not for me" list. */
final class RecommendationsScreen extends SectionScreen {
    static final String ID = "recommendations";

    RecommendationsScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Рекомендации", "Recommendations");
    }

    @Override
    String description() {
        return text("Моя волна, дубликаты, «Не нравится»", "My Wave, duplicates, dislikes");
    }

    @Override
    String iconKey() {
        return "recs";
    }

    @Override
    String summary() {
        return text("Скрывать дубликаты: ", "Hide duplicates: ")
                + (BoolSettings.DUPLICATE_FILTER.get() ? text("да", "yes") : text("нет", "no"));
    }

    @Override
    void fill(LinearLayout list) {
        addWaveOptions(list);
        list.addView(ui.createSubHeading(text("Дубликаты", "Duplicates")));
        SubRows duplicateOptions = ui.createSubRows();
        list.addView(ui.createToggleRow(
                text("Скрывать дубликаты", "Hide duplicates"),
                text("Один и тот же трек, перезалитый разными людьми, показывается на главной, в автовоспроизведении "
                                + "и в подборках, которые SoundCloud собрал для вас (Your Mix, Daily Drops, Weekly Wave), один раз. Одинаковыми считаются треки с тем же названием и длительностью (разница до 2 с). "
                                + "Лайки, плейлисты и профили не меняются.",
                        "The same song re-uploaded by different users appears once on the home screen and in autoplay. "
                                + "Tracks with the same title and duration (within 2 s) count as the same."),
                BoolSettings.DUPLICATE_FILTER,
                duplicateOptions
        ).icon("duplicates").brief(text("Перезалитый трек показывается один раз.", "A re-uploaded song appears once.")));
        duplicateOptions.addView(ui.createToggleRow(
                text("Считать slowed, sped up и ремиксы тем же треком", "Treat slowed, sped up and remixes as the same song"),
                text("Версии с другой скоростью («0.9 speed», «1.2x», slowed) и ремиксы считаются тем же треком, "
                                + "даже если длительность отличается. Если выключено, такие версии показываются отдельно.",
                        "Versions at another speed (\"0.9 speed\", \"1.2x\", slowed) and remixes count as the same song, "
                                + "even with another duration. When off, these versions are shown separately."),
                BoolSettings.MERGE_EDITED_VERSIONS
        ));
        list.addView(duplicateOptions);
        addDislikeOptions(list);
    }

    /** "My Wave" has a screen of its own: the row opens it. */
    private void addWaveOptions(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Моя волна", "My Wave")));
        list.addView(ui.openScreenRow(
                text("Моя волна", "My Wave"),
                text("Вкладка внизу экрана, источники и память волны. Сама волна включается на её вкладке, сразу после «Главной».",
                        "The tab of the bottom bar, the sources and what the wave remembers. The wave itself starts on its tab, "
                                + "right after Home."),
                WaveScreen.ID).icon("recs"));
    }

    private void addDislikeOptions(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Не нравится", "Not for me")));
        // The list of marked tracks is a page of its own: it is filled again after every change.
        LinearLayout disliked = SettingsPage.nested(ui);
        list.addView(ui.createToggleRow(
                text("«Не нравится» у треков", "\"Not for me\" on tracks"),
                text("В меню «⋮» трека появляется «Не нравится — не рекомендовать». Такой трек пропадает с главной, из "
                                + "автовоспроизведения и из подборок SoundCloud (Your Mix, Daily Drops). Лайки, плейлисты и поиск не меняются; список хранится на телефоне.",
                        "The track menu gets \"Not for me: don't recommend\". Such a track disappears from the home screen and "
                                + "autoplay. Likes, playlists and search stay as they are; the list is kept on the phone."),
                TrackDislikes.isEnabled(),
                (button, checked) -> {
                    Settings.putBoolean(TrackDislikes.ENABLED, checked);
                    disliked.setVisibility(checked ? View.VISIBLE : View.GONE);
                }
        ).icon("dislike").brief(text("В меню трека появится «Не нравится — не рекомендовать».",
                "The track menu gets \"Not for me: don't recommend\".")));
        disliked.setVisibility(TrackDislikes.isEnabled() ? View.VISIBLE : View.GONE);
        list.addView(disliked);
        showDisliked(disliked);
    }

    /** Rows shown at once; the rest come with "Show more". */
    private static final int DISLIKED_PAGE = 50;

    private void showDisliked(LinearLayout container) {
        container.removeAllViews();
        // The marked tracks are not settings: the search index must not list them as rows.
        if (ui.search.isIndexing()) return;
        Map<String, String> all = TrackDislikes.getAll();
        container.addView(dislikedHeading(all.size()));
        if (all.isEmpty()) {
            TextView empty = ui.createText("Body.Secondary", text("Пока пусто.", "Nothing yet."));
            empty.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), ui.dimen("spacing_s"));
            container.addView(empty);
            return;
        }
        addDislikedRows(container, new java.util.ArrayList<>(all.entrySet()), 0);
    }

    private View dislikedHeading(int count) {
        return ui.createSubHeading(text("Список", "List") + " (" + count + ")");
    }

    /**
     * Adds a page of rows from {@code from}, and a "Show more" row when more are left. A row taken back goes alone:
     * the whole list was made again after each one.
     */
    private void addDislikedRows(LinearLayout container, java.util.List<Map.Entry<String, String>> entries, int from) {
        int to = Math.min(entries.size(), from + DISLIKED_PAGE);
        for (int i = from; i < to; i++) {
            Map.Entry<String, String> entry = entries.get(i);
            View[] row = new View[1];
            row[0] = ui.createActionRow(entry.getValue(), text("Нажмите, чтобы вернуть в рекомендации", "Tap to allow in recommendations again"), v -> {
                TrackDislikes.remove(entry.getKey());
                int left = TrackDislikes.getAll().size();
                if (left == 0) {
                    showDisliked(container);
                    return;
                }
                // The page put the row into a card: it is taken out of that.
                removeFromParent(row[0]);
                container.removeViewAt(0);
                container.addView(dislikedHeading(left), 0);
            });
            container.addView(row[0]);
        }
        if (to >= entries.size()) return;
        View[] more = new View[1];
        more[0] = ui.createActionRow(text("Показать ещё", "Show more") + " (" + (entries.size() - to) + ")", "", v -> {
            removeFromParent(more[0]);
            addDislikedRows(container, entries, to);
        });
        container.addView(more[0]);
    }

    private static void removeFromParent(View view) {
        if (view.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) view.getParent()).removeView(view);
    }
}
