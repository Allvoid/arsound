package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.text.InputType;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/**
 * The countries of the IP country block: a tick for the usual ones and a row for codes of any other.
 */
final class RegionGuardRows {
    private final ReVancedSettingsActivity activity;
    private final SettingsUi ui;

    RegionGuardRows(SettingsUi ui) {
        this.ui = ui;
        this.activity = ui.activity;
    }

    /** The countries offered with a tick: the neighbours most users ask about. Others are typed in by code. */
    private static final String[] GUARD_COUNTRIES = {"RU", "UA", "BY", "KZ"};

    private String guardCountryName(String code) {
        switch (code) {
            case "RU":
                return text("Россия", "Russia");
            case "UA":
                return text("Украина", "Ukraine");
            case "BY":
                return text("Беларусь", "Belarus");
            default:
                return text("Казахстан", "Kazakhstan");
        }
    }

    /** One tick per country, and a row for codes of any other countries. */
    void addTo(LinearLayout list) {
        TextView label = ui.createText("H6.Special", text("Страны", "Countries"));
        label.setTextColor(ui.accent());
        label.setTextSize(12);
        label.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(2));
        list.addView(label);
        for (String code : GUARD_COUNTRIES) {
            list.addView(ui.createCheckRow(guardCountryName(code) + " (" + code + ")",
                    Settings.getRegionGuardCountries().contains(code),
                    (button, checked) -> {
                        Set<String> codes = new LinkedHashSet<>(Settings.getRegionGuardCountries());
                        if (checked) codes.add(code);
                        else codes.remove(code);
                        Settings.setRegionGuardCountries(codes);
                        warnIfNoGuardCountries();
                    }));
        }
        TextView[] description = new TextView[1];
        ActionRow others = ui.createActionRow(text("Другие страны", "Other countries"), "",
                v -> editOtherGuardCountries(description[0]));
        description[0] = others.description();
        description[0].setText(otherGuardCountriesDescription());
        others.chevron();
        list.addView(others);
    }

    /** The switch stays on with no country left, and then blocks nothing: said once the last tick goes. */
    private void warnIfNoGuardCountries() {
        if (!Settings.isRegionGuardEnabled() || !Settings.getRegionGuardCountries().isEmpty()) return;
        Toast.makeText(activity, text("Ни одна страна не отмечена: блок по стране не действует",
                "No country is ticked: the country block does nothing"), Toast.LENGTH_LONG).show();
    }

    private static List<String> otherGuardCountries() {
        List<String> others = new ArrayList<>(Settings.getRegionGuardCountries());
        others.removeAll(Arrays.asList(GUARD_COUNTRIES));
        return others;
    }

    private String otherGuardCountriesDescription() {
        List<String> others = otherGuardCountries();
        return others.isEmpty()
                ? text("Двухбуквенные коды стран через запятую, например DE, PL", "Two-letter country codes separated by commas, for example DE, PL")
                : TextUtils.join(", ", others);
    }

    private void editOtherGuardCountries(TextView description) {
        EditText codes = new EditText(activity);
        codes.setHint("DE, PL");
        codes.setSingleLine(true);
        codes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        codes.setText(TextUtils.join(", ", otherGuardCountries()));
        codes.setSelection(codes.length());
        LinearLayout form = new LinearLayout(activity);
        form.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), 0);
        form.addView(codes, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new ArsoundDialog(activity)
                .setTitle(text("Другие страны", "Other countries"))
                .setMessage(text("Двухбуквенные коды стран, как в Cloudflare: DE — Германия, PL — Польша.",
                        "Two-letter country codes, as Cloudflare reports them: DE is Germany, PL is Poland."))
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String input = codes.getText().toString().trim().toUpperCase(Locale.US);
                    if (!input.isEmpty() && !input.matches("[A-Z]{2}(\\s*[,;\\s]\\s*[A-Z]{2})*[,;\\s]*")) {
                        Toast.makeText(activity, text("Нужны коды из двух латинских букв через запятую",
                                "Use two-letter codes separated by commas"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    Set<String> chosen = new LinkedHashSet<>();
                    for (String code : GUARD_COUNTRIES) {
                        if (Settings.getRegionGuardCountries().contains(code)) chosen.add(code);
                    }
                    for (String code : input.split("[,;\\s]+")) {
                        if (code.matches("[A-Z]{2}")) chosen.add(code);
                    }
                    Settings.setRegionGuardCountries(chosen);
                    description.setText(otherGuardCountriesDescription());
                    warnIfNoGuardCountries();
                })
                .show();
    }
}
