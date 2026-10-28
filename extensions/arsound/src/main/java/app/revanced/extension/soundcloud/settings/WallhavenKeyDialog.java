package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Dialog;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.HelpBadge;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.wallpaper.Wallhaven;

/**
 * The two rare settings of the Wallhaven catalogue, which live in the filter sheet of {@link WallhavenScreen}: the API
 * key (needed only for 18+ pictures) and the question "are you 18" that guards the 18+ switch. They were rows of the
 * appearance screen, where most users never need them.
 */
final class WallhavenKeyDialog {
    private WallhavenKeyDialog() {
    }

    /** What the key row says about the key. */
    static String summary() {
        return Wallhaven.key().isEmpty()
                ? text("Не задан. Без ключа доступны только безопасные картинки.",
                "Not set. Without a key only safe pictures are offered.")
                : text("Задан. Хранится только на этом телефоне.", "Set. It stays on this phone only.");
    }

    /** The row "API key" with its help badge; it opens {@link #edit}. */
    static ActionRow createRow(SettingsUi ui, Runnable onChanged) {
        return ui.createActionRow(
                text("API-ключ Wallhaven", "Wallhaven API key"),
                summary(),
                HelpBadge.create(ui.activity, ui.createText("H4.Primary", "").getCurrentTextColor(),
                        text("Зачем ключ Wallhaven", "What the Wallhaven key is for"),
                        text("Ключ не обязателен: безопасные обои ищутся без него.",
                                "The key is optional: safe wallpapers are found without it."),
                        text("Он нужен только для картинок 18+. Ключ берётся на wallhaven.cc в настройках аккаунта.",
                                "It is only needed for 18+ pictures. The key comes from your account settings on wallhaven.cc."),
                        text("Ключ хранится только на этом телефоне и уходит только на wallhaven.cc в заголовке запроса. "
                                        + "В журнал он не пишется.",
                                "The key stays on this phone and goes only to wallhaven.cc in a request header. It is never logged.")),
                v -> edit(ui, onChanged));
    }

    /** The switch "Show 18+" with the question that guards it. {@code onChanged} hears the change after it was kept. */
    static ToggleRow createAdultRow(SettingsUi ui, Runnable onChanged) {
        return ui.createToggleRow(
                text("Показывать 18+", "Show 18+"),
                text("Картинки Sketchy и NSFW в каталоге Wallhaven. Только с ключом и только для совершеннолетних.",
                        "Sketchy and NSFW pictures in the Wallhaven catalogue. Only with a key, and only for adults."),
                Settings.getBoolean(Settings.WALLHAVEN_ADULT, false),
                (button, checked) -> {
                    if (checked == Settings.getBoolean(Settings.WALLHAVEN_ADULT, false)) return;
                    if (!checked) {
                        Settings.putBoolean(Settings.WALLHAVEN_ADULT, false);
                        onChanged.run();
                        return;
                    }
                    new ArsoundDialog(ui.activity)
                            .setTitle(text("Вам есть 18 лет?", "Are you 18 or older?"))
                            .setMessage(text("В каталоге появятся картинки Sketchy и NSFW. Они только для совершеннолетних.",
                                    "Sketchy and NSFW pictures will appear in the catalogue. They are for adults only."))
                            .setCancelable(false)
                            .setNegativeButton(text("Мне нет 18", "I am under 18"), (dialog, which) -> button.setChecked(false))
                            .setPositiveButton(text("Мне есть 18", "I am 18 or older"), (dialog, which) -> {
                                Settings.putBoolean(Settings.WALLHAVEN_ADULT, true);
                                onChanged.run();
                            })
                            .show();
                });
    }

    /** The key field with a check button; the check asks the service and tells whether it knows the key. */
    static void edit(SettingsUi ui, Runnable onChanged) {
        LinearLayout form = ui.createColumn();
        form.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), 0);
        EditText field = new EditText(ui.activity);
        field.setSingleLine(true);
        field.setHint(text("Ключ из настроек аккаунта wallhaven.cc", "Key from your wallhaven.cc account settings"));
        field.setText(Wallhaven.key());
        // The keyboard must not learn or suggest the key.
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        form.addView(field);

        TextView result = ui.createText("Body.Secondary", "");
        result.setTag(ArsoundDialog.KEEP_COLORS);
        result.setTextColor(ArsoundTheme.palette(ui.activity, "secondary"));
        result.setPadding(0, ui.dp(10), 0, 0);
        View check = ui.createPillButton(text("Проверить ключ", "Check the key"), false, v -> {
            String key = Wallhaven.clean(field.getText().toString());
            result.setTextColor(ArsoundTheme.palette(ui.activity, "secondary"));
            if (key.isEmpty()) {
                result.setText(text("Сначала вставьте ключ.", "Paste the key first."));
                return;
            }
            result.setText(text("Проверяю…", "Checking…"));
            Utils.runOnBackgroundThread(() -> {
                Wallhaven.KeyState state = Wallhaven.checkKey(key);
                Utils.runOnMainThread(() -> {
                    boolean valid = state == Wallhaven.KeyState.VALID;
                    result.setTextColor(ArsoundTheme.palette(ui.activity, valid ? "special" : "error"));
                    result.setText(valid ? text("Ключ принят.", "The key is accepted.")
                            : state == Wallhaven.KeyState.INVALID
                            ? text("Wallhaven не знает этот ключ (ошибка 401).", "Wallhaven does not know this key (error 401).")
                            : text("Не удалось проверить: нет связи с Wallhaven.", "Could not check: no connection to Wallhaven."));
                });
            });
        });
        check.setTag(ArsoundDialog.KEEP_COLORS);
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        checkParams.topMargin = ui.dp(10);
        checkParams.leftMargin = -ui.dp(8);
        form.addView(check, checkParams);
        form.addView(result);

        ArsoundDialog dialog = new ArsoundDialog(ui.activity)
                .setTitle(text("API-ключ Wallhaven", "Wallhaven API key"))
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                // Stays open when the key is refused, so what was typed is not lost; closed here once it is kept.
                .setPositiveButtonKeepingOpen(text("Сохранить", "Save"), (d, which) -> {
                    String typed = field.getText().toString().trim();
                    String key = Wallhaven.clean(typed);
                    if (!typed.isEmpty() && key.isEmpty()) {
                        Toast.makeText(ui.activity, text("Это не похоже на ключ: только буквы и цифры",
                                "That does not look like a key: letters and digits only"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    save(key);
                    onChanged.run();
                    d.dismiss();
                });
        if (!Wallhaven.key().isEmpty()) {
            dialog.setNeutralButton(text("Удалить ключ", "Remove the key"), (d, which) -> {
                save("");
                onChanged.run();
            });
        }
        Dialog shown = dialog.show();
        if (shown.getWindow() != null) {
            shown.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    /** Keeps the key (an empty one removes it). Without a key there is no 18+ mode. */
    private static void save(String key) {
        Settings.putString(Settings.WALLHAVEN_KEY, key);
        if (key.isEmpty()) Settings.putBoolean(Settings.WALLHAVEN_ADULT, false);
    }
}
