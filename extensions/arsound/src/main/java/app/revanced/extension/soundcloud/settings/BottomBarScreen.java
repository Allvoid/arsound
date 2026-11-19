package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.navbar.TabChoice;
import app.revanced.extension.soundcloud.navbar.TabKind;

/**
 * Which tabs the bottom bar of SoundCloud has: a tick per tab, Library always on. A change applies after the app
 * restarts, and the screen offers the restart as soon as something changed. Hooks: {@code navbar/BottomTabs}.
 */
final class BottomBarScreen extends SectionScreen {
    static final String ID = "bottombar";

    BottomBarScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Нижняя панель", "Bottom bar");
    }

    @Override
    String description() {
        return text("Какие вкладки показывать внизу, в каком порядке и на какой запускаться",
                "Which tabs the bottom bar shows, in what order, and which one the app starts on");
    }

    @Override
    String iconKey() {
        return "home";
    }

    @Override
    String keywords() {
        return text("вкладки панель меню навигация лента убрать скрыть порядок переставить стартовая запуск главная поиск библиотека upgrade",
                "tabs bar menu navigation feed remove hide order reorder move start launch home search library upgrade");
    }

    @Override
    String summary() {
        int shown = 1;
        for (TabKind kind : TabKind.values()) if (!kind.hiddenSetting().get()) shown++;
        if (!BoolSettings.HIDE_UPGRADE_TAB.get()) shown++;
        if (!BoolSettings.HIDE_TAB_WAVE.get()) shown++;
        return text("Вкладок: ", "Tabs: ") + shown;
    }

    @Override
    void fill(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Вкладки", "Tabs")));
        TextView note = ui.createText("Body.Secondary", text(
                "Отметьте вкладки, которые нужны, и стрелками расставьте их в нужном порядке. Библиотека остаётся "
                        + "всегда: если оставить только её, приложение откроется на ней. Приложение запускается на "
                        + "стартовой вкладке (ниже), а если она не выбрана, то на первой из оставшихся.",
                "Tick the tabs you want and move them with the arrows into the order you like. Library always stays: "
                        + "leave only it and the app opens on it. The app starts on the start tab (below), or on the "
                        + "first of the tabs that stay when none is chosen."));
        note.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(10));
        list.addView(note);

        View[] restart = new View[1];
        Runnable changed = () -> restart[0].setVisibility(View.VISIBLE);

        // The tabs in the user's order, each with arrows that move it; the list is built again after a move.
        LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.VERTICAL);
        list.addView(tabs);
        fillTabs(tabs, changed);
        list.addView(ui.createChoiceRow(text("Стартовая вкладка", "Start tab"),
                text("Вкладка, которая открывается при запуске приложения. «Первая» — первая вкладка панели. "
                                + "Ссылки и ярлыки открывают своё, не стартовую. Если выбранная вкладка скрыта, "
                                + "открывается первая.",
                        "The tab that opens when the app starts. \"First\" is the bar's first tab. Links and shortcuts "
                                + "open their own tab, not the start one. If the chosen tab is hidden, the first tab opens."),
                TabChoice.START_KEY_CODE, 0, new long[]{0, 1, 2, 3, 4, 5},
                new String[]{text("Первая", "First"), text("Главная", "Home"), text("Моя волна", "My Wave"),
                        text("Лента", "Feed"), text("Поиск", "Search"), text("Библиотека", "Library")},
                () -> {
                    TabChoice.saveStart(TabChoice.keyOfCode(Settings.getLong(TabChoice.START_KEY_CODE, 0)));
                    changed.run();
                }).icon("home"));
        list.addView(tabRow("subscription", text("Upgrade (если есть)", "Upgrade (if any)"), BoolSettings.HIDE_UPGRADE_TAB, changed));

        TextView searchNote = ui.createText("Body.Secondary", text(
                "Без вкладки «Поиск» кнопка поиска появляется вверху Библиотеки.",
                "Without the Search tab a search button appears at the top of the Library."));
        searchNote.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), 0);
        list.addView(searchNote);

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(32));
        TextView hint = ui.createText("Body.Secondary", text("Изменения применятся после перезапуска приложения.",
                "The changes apply after the app restarts."));
        hint.setGravity(Gravity.CENTER);
        bar.addView(hint);
        View button = ui.createPillButton(text("Перезапустить сейчас", "Restart now"), true, v -> {
            v.setEnabled(false);
            Context context = activity.getApplicationContext();
            // The ticks must be on the disk before the process ends; writing them waits for the disk, not here.
            Utils.runOnBackgroundThread(() -> {
                Settings.flush();
                Utils.runOnMainThread(() -> Utils.restartApp(context));
            });
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = ui.dp(10);
        bar.addView(button, params);
        bar.setVisibility(View.GONE);
        restart[0] = bar;
        list.addView(bar);
    }

    /** The rows of the five tabs in the user's order: a tick, and arrows that move the tab up or down. */
    private void fillTabs(LinearLayout tabs, Runnable changed) {
        tabs.removeAllViews();
        List<String> order = TabChoice.orderOrDefault();
        for (int i = 0; i < order.size(); i++) {
            String key = order.get(i);
            ToggleRow row;
            if (TabChoice.LIBRARY.equals(key)) {
                row = ui.createCheckRow(text("Библиотека", "Library"), true, (button, checked) -> {
                });
                row.icon("library");
                row.lock();
            } else if (TabChoice.HOME.equals(key)) {
                row = tabRow("home", text("Главная", "Home"), TabKind.HOME.hiddenSetting(), changed);
            } else if (TabChoice.WAVE.equals(key)) {
                row = tabRow("recs", text("Моя волна", "My Wave"), BoolSettings.HIDE_TAB_WAVE, changed);
            } else if (TabChoice.FEED.equals(key)) {
                row = tabRow("feed", text("Лента", "Feed"), TabKind.FEED.hiddenSetting(), changed);
            } else {
                row = tabRow("search", text("Поиск", "Search"), TabKind.SEARCH.hiddenSetting(), changed);
            }
            int place = i;
            row.addTrailing(arrow("\u2191", text("Выше", "Move up"), place > 0, () -> move(tabs, order, place, -1, changed)),
                    ui.dp(40), ui.dp(40));
            row.addTrailing(arrow("\u2193", text("Ниже", "Move down"), place < order.size() - 1,
                    () -> move(tabs, order, place, 1, changed)), ui.dp(40), ui.dp(40));
            tabs.addView(row);
        }
    }

    private void move(LinearLayout tabs, List<String> order, int place, int step, Runnable changed) {
        List<String> moved = new ArrayList<>(order);
        Collections.swap(moved, place, place + step);
        TabChoice.saveOrder(moved);
        changed.run();
        fillTabs(tabs, changed);
    }

    /** A small arrow button; dimmed and inert at the end of the list. */
    private TextView arrow(String glyph, String description, boolean enabled, Runnable action) {
        TextView button = ui.createText("H4.Primary", glyph);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setAlpha(enabled ? 1f : .25f);
        button.setEnabled(enabled);
        if (enabled) button.setOnClickListener(v -> action.run());
        return button;
    }

    /** A tick that is on while the tab stays; the setting stores the opposite, "hidden". */
    private ToggleRow tabRow(String iconKey, String name, BoolSetting hidden, Runnable changed) {
        CompoundButton.OnCheckedChangeListener listener = (button, checked) -> {
            hidden.set(!checked);
            changed.run();
        };
        ToggleRow row = ui.createCheckRow(name, !hidden.get(), listener);
        row.icon(iconKey);
        return row;
    }
}
