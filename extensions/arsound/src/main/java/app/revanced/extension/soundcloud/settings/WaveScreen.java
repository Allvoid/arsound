package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import app.revanced.extension.soundcloud.player.TrackTitleResolver;
import app.revanced.extension.soundcloud.recommendations.MyWave;
import app.revanced.extension.soundcloud.recommendations.WaveMemory;
import app.revanced.extension.soundcloud.recommendations.WaveSource;
import app.revanced.extension.soundcloud.theme.WaveCard;

/**
 * "My Wave": the big start/stop card, what the wave is doing, where it gets its tracks (ticked) and the tracks it played
 * last. Opened by the wave button in the top bar of the Library (SoundCloud's bottom bar cannot take a tab of its own: its
 * screens are Compose pages made by SoundCloud) and from the settings.
 */
final class WaveScreen extends SectionScreen {
    static final String ID = "wave";
    private static final int RECENT = 12;

    private TextView status;
    private LinearLayout recent;
    private boolean scheduled;

    WaveScreen(SettingsUi ui) {
        super(ui);
    }

    /** The intent that opens this screen from anywhere in the app. */
    public static Intent intent(Context context) {
        return new Intent(context, ReVancedSettingsActivity.class).putExtra(ReVancedSettingsActivity.EXTRA_SCREEN, ID);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Моя волна", "My Wave");
    }

    @Override
    String description() {
        return text("Музыка по вашему вкусу без конца: включить, источники, что играло",
                "Music to your taste without end: start, sources, what played");
    }

    @Override
    String iconKey() {
        return "recs";
    }

    @Override
    String keywords() {
        return text("волна рекомендации бесконечно лайки last.fm deezer похожие радио",
                "wave recommendations endless likes last.fm deezer related radio");
    }

    @Override
    String summary() {
        return MyWave.isActive() ? text("Играет", "Playing") : text("Выключена", "Off");
    }

    @Override
    void fill(LinearLayout list) {
        list.addView(new WaveCard(activity, true), cardParams());
        status = ui.createText("Body.Secondary", "");
        status.setPadding(ui.dimen("spacing_m"), ui.dp(10), ui.dimen("spacing_m"), 0);
        list.addView(status);

        list.addView(ui.createSubHeading(text("Откуда берутся треки", "Where the tracks come from")));
        TextView note = ui.createText("Body.Secondary", text(
                "Хотя бы один источник остаётся включённым. Образцы — ваши лайки и треки, дослушанные хотя бы 45 секунд; "
                        + "пропущенные в первые 15 секунд и «Не нравится» не предлагаются.",
                "At least one source stays on. Seeds are your likes and the tracks heard at least 45 seconds; tracks skipped in "
                        + "the first 15 seconds and \"Not for me\" are never offered."));
        note.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), ui.dimen("spacing_s"));
        list.addView(note);
        for (WaveSource source : WaveSource.values()) {
            ToggleRow[] row = new ToggleRow[1];
            row[0] = ui.createCheckRow(source.label(), source.isOn(), (button, checked) -> {
                if (!checked && WaveSource.onCount() <= 1) {
                    row[0].setChecked(true);
                    return;
                }
                source.set(checked);
            });
            row[0].icon("recs");
            list.addView(row[0]);
        }
        TextView memory = ui.createText("Body.Secondary", text(
                "Запомнено: пропущенных " + WaveMemory.skippedCount() + ", дослушанных " + WaveMemory.heardCount() + ".",
                "Remembered: " + WaveMemory.skippedCount() + " skipped, " + WaveMemory.heardCount() + " heard."));
        memory.setPadding(ui.dimen("spacing_m"), ui.dimen("spacing_s"), ui.dimen("spacing_m"), 0);
        list.addView(memory);
        ActionRow forget = ui.createActionRow(text("Забыть пропущенные и дослушанные", "Forget skipped and heard tracks"),
                text("Волна начнёт с чистого листа. «Не нравится» и лайки не меняются.",
                        "The wave starts afresh. Dislikes and likes stay as they are."),
                v -> {
                    WaveMemory.forget();
                    memory.setText(text("Запомнено: пропущенных 0, дослушанных 0.", "Remembered: 0 skipped, 0 heard."));
                });
        forget.icon("trash");
        list.addView(forget);

        list.addView(ui.createSubHeading(text("Играло в волне", "Played in the wave")));
        recent = new LinearLayout(activity);
        recent.setOrientation(LinearLayout.VERTICAL);
        list.addView(recent);
        refresh();
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = ui.dp(8);
        params.leftMargin = ui.dp(16);
        params.rightMargin = ui.dp(16);
        return params;
    }

    @Override
    void onResume() {
        refresh();
    }

    /** The status line and the list of the last tracks; looked at again while the screen is open. */
    private void refresh() {
        if (status == null || recent == null) return;
        int added = MyWave.addedCount();
        status.setText(MyWave.isActive()
                ? text("Играет, добавлено треков: " + added, "Playing, tracks added: " + added)
                : added > 0 ? text("Остановлена, в последний раз добавлено треков: " + added, "Stopped, tracks added last time: " + added)
                : text("Выключена. Нажмите «Включить».", "Off. Tap \"Play\"."));
        List<String> urns = MyWave.recent(RECENT);
        // Titles come from SoundCloud's own database, off the main thread.
        app.revanced.extension.shared.Utils.runOnBackgroundThread(() -> {
            Map<String, String[]> titles = TrackTitleResolver.storedTitles(urns);
            app.revanced.extension.shared.Utils.runOnMainThread(() -> showRecent(urns, titles));
        });
        // The screen looks again every few seconds while it is open, so "added N" and the list move on.
        if (!scheduled) {
            scheduled = true;
            status.postDelayed(() -> {
                scheduled = false;
                if (!activity.isFinishing() && !activity.isDestroyed()) refresh();
            }, 3000);
        }
    }

    private void showRecent(List<String> urns, Map<String, String[]> titles) {
        if (recent == null || activity.isFinishing() || activity.isDestroyed()) return;
        recent.removeAllViews();
        if (urns.isEmpty()) {
            TextView none = ui.createText("Body.Secondary", text("Пока ничего.", "Nothing yet."));
            none.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), 0);
            recent.addView(none);
            return;
        }
        List<String> newestFirst = new ArrayList<>(urns);
        Collections.reverse(newestFirst);
        for (String urn : newestFirst) {
            String[] found = titles.get(urn);
            String label = found == null ? text("Трек SoundCloud", "SoundCloud track") : found[0] + (found[1] == null || found[1].isEmpty() ? "" : " — " + found[1]);
            TextView line = ui.createText("Body.Primary", label);
            line.setPadding(ui.dimen("spacing_m"), ui.dp(6), ui.dimen("spacing_m"), ui.dp(6));
            line.setSingleLine(true);
            line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            recent.addView(line);
        }
    }
}
