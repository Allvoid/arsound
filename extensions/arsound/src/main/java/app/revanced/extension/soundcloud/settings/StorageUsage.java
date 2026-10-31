package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.graphics.drawable.GradientDrawable;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.lyrics.LyricsRepository;
import app.revanced.extension.soundcloud.player.StreamCache;
import app.revanced.extension.soundcloud.shared.Colors;

/**
 * The card at the top of the storage screen: one bar that shows how the space is shared between the downloaded tracks,
 * the stream cache and the saved lyrics, and a legend with the sizes. The sizes are counted in the background. Made by
 * {@link StorageScreen}.
 */
final class StorageUsage {
    private final SettingsUi ui;

    StorageUsage(SettingsUi ui) {
        this.ui = ui;
    }

    View create() {
        SettingsGroup card = new SettingsGroup(ui);
        card.setLayoutParams(SettingsGroup.pageParams(ui));
        LinearLayout body = ui.createColumn();
        body.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(14));
        card.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout top = new LinearLayout(ui.activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(ui.createText("BodyLarge.Primary", text("Занято приложением", "Used by the app")),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView total = ui.createText("BodyLarge.Primary", "…");
        total.setTextColor(ui.accent());
        top.addView(total);
        body.addView(top);

        int[] colors = {ui.accent(), Colors.blend(ui.accent(), ui.secondary(), 0.5f), ui.secondary()};
        UsageBar bar = new UsageBar(ui.activity, Colors.withAlpha(ui.secondary(), 0x33));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(10));
        barParams.topMargin = ui.dp(12);
        barParams.bottomMargin = ui.dp(6);
        body.addView(bar, barParams);

        String[] names = {text("Скачанные треки", "Downloaded tracks"), text("Кэш потоков", "Stream cache"),
                text("Кэш текстов", "Lyrics cache")};
        TextView[] sizes = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            LinearLayout line = new LinearLayout(ui.activity);
            line.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(ui.activity);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(colors[i]);
            dot.setBackground(circle);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8));
            dotParams.rightMargin = ui.dp(10);
            line.addView(dot, dotParams);
            line.addView(ui.createText("Body.Secondary", names[i]), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            sizes[i] = ui.createText("Body.Primary", "…");
            line.addView(sizes[i]);
            line.setMinimumHeight(ui.dp(28));
            body.addView(line);
        }

        ui.fillInBackground(() -> {
            long[] parts = {StorageScreen.downloadsUsage()[1], StreamCache.usedBytes() + StreamCache.otherCachesBytes(),
                    LyricsRepository.storedBytes()};
            long sum = parts[0] + parts[1] + parts[2];
            Utils.runOnMainThread(() -> {
                if (ui.activity.isFinishing() || ui.activity.isDestroyed()) return;
                total.setText(Formatter.formatShortFileSize(ui.activity, sum));
                for (int i = 0; i < parts.length; i++) sizes[i].setText(Formatter.formatShortFileSize(ui.activity, parts[i]));
                bar.setParts(parts, colors);
            });
        });
        return card;
    }
}
