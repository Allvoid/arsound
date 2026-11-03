package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.shared.Utils;
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
        return text("Какие вкладки показывать внизу: главная, лента, поиск, библиотека",
                "Which tabs the bottom bar shows: home, feed, search, library");
    }

    @Override
    String iconKey() {
        return "home";
    }

    @Override
    String keywords() {
        return text("вкладки панель меню навигация лента убрать скрыть главная поиск библиотека upgrade",
                "tabs bar menu navigation feed remove hide home search library upgrade");
    }

    @Override
    String summary() {
        int shown = 1;
        for (TabKind kind : TabKind.values()) if (!kind.hiddenSetting().get()) shown++;
        if (!BoolSettings.HIDE_UPGRADE_TAB.get()) shown++;
        return text("Вкладок: ", "Tabs: ") + shown;
    }

    @Override
    void fill(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Вкладки", "Tabs")));
        TextView note = ui.createText("Body.Secondary", text(
                "Отметьте вкладки, которые нужны. Библиотека остаётся всегда: если оставить только её, "
                        + "приложение откроется на ней. Первая из оставшихся вкладок — та, на которой приложение "
                        + "запускается.",
                "Tick the tabs you want. Library always stays: leave only it and the app opens on it. "
                        + "The first of the tabs that stay is the one the app starts on."));
        note.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(10));
        list.addView(note);

        View[] restart = new View[1];
        Runnable changed = () -> restart[0].setVisibility(View.VISIBLE);

        list.addView(tabRow("home", text("Главная", "Home"), TabKind.HOME.hiddenSetting(), changed));
        list.addView(tabRow("feed", text("Лента", "Feed"), TabKind.FEED.hiddenSetting(), changed));
        list.addView(tabRow("search", text("Поиск", "Search"), TabKind.SEARCH.hiddenSetting(), changed));
        ToggleRow library = ui.createCheckRow(text("Библиотека", "Library"), true, (button, checked) -> {
        });
        library.icon("library");
        library.lock();
        list.addView(library);
        list.addView(tabRow("recs", text("Моя волна (значок в Библиотеке)", "My Wave (icon in the Library)"), BoolSettings.HIDE_WAVE_BUTTON, changed));
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
            Settings.flush();
            Utils.restartApp(activity.getApplicationContext());
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = ui.dp(10);
        bar.addView(button, params);
        bar.setVisibility(View.GONE);
        restart[0] = bar;
        list.addView(bar);
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
