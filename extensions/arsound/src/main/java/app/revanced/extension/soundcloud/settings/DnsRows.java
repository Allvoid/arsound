package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.network.CustomDns;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/**
 * The custom DNS rows of the network screen.
 */
final class DnsRows {
    /** The servers offered. The stored values are the ones of {@link CustomDns}; they are in the saved settings. */
    private enum Preset {
        XBOX(CustomDns.PRESET_XBOX) {
            @Override
            String label() {
                return CustomDns.XBOX_DOH_HOST;
            }
        },
        CUSTOM(CustomDns.PRESET_CUSTOM) {
            @Override
            String label() {
                return text("Свой сервер", "Custom server");
            }
        };

        final String stored;

        Preset(String stored) {
            this.stored = stored;
        }

        abstract String label();

        static String[] labels() {
            String[] labels = new String[values().length];
            for (int i = 0; i < labels.length; i++) labels[i] = values()[i].label();
            return labels;
        }
    }

    /** How names are looked up. */
    private enum Mode {
        AUTO(CustomDns.MODE_AUTO) {
            @Override
            String label() {
                return text("Авто: сначала DoH, потом обычный", "Auto: DoH first, then plain");
            }
        },
        DOH(CustomDns.MODE_DOH) {
            @Override
            String label() {
                return text("Только DNS-over-HTTPS", "DNS-over-HTTPS only");
            }
        },
        PLAIN(CustomDns.MODE_PLAIN) {
            @Override
            String label() {
                return text("Только обычный DNS", "Plain DNS only");
            }
        };

        final String stored;

        Mode(String stored) {
            this.stored = stored;
        }

        abstract String label();

        static String[] labels() {
            String[] labels = new String[values().length];
            for (int i = 0; i < labels.length; i++) labels[i] = values()[i].label();
            return labels;
        }

        /** The mode stored as {@code stored}; auto for anything else. */
        static Mode of(String stored) {
            for (Mode mode : values()) if (mode.stored.equals(stored)) return mode;
            return AUTO;
        }
    }

    private final ReVancedSettingsActivity activity;
    private final SettingsUi ui;

    DnsRows(SettingsUi ui) {
        this.ui = ui;
        this.activity = ui.activity;
    }

    void addTo(LinearLayout list) {
        SubRows options = ui.createSubRows();
        list.addView(ui.createToggleRow(
                text("Свой DNS", "Custom DNS"),
                text("Приложение узнаёт адреса серверов через выбранный DNS, а не через DNS провайдера. "
                                + "Помогает, если провайдер режет или подменяет SoundCloud. Если сервер не отвечает, "
                                + "используется обычный DNS.",
                        "The app resolves server addresses through the chosen DNS instead of the provider DNS. "
                                + "If the server does not answer, the normal DNS is used."),
                BoolSettings.CUSTOM_DNS,
                options,
                (button, checked) -> CustomDns.clearCache()
        ).icon("dns").brief(text("Адреса серверов узнаются через выбранный DNS, а не DNS провайдера.",
                "Server addresses are resolved through the chosen DNS, not the provider's.")));
        list.addView(options);

        TextView[] serverDescription = new TextView[1];
        ActionRow serverRow = ui.createActionRow(text("Сервер", "Server"), dnsServerDescription(), v -> {
            new ArsoundDialog(activity)
                    .setTitle(text("Сервер DNS", "DNS server"))
                    .setItems(Preset.labels(), (dialog, which) -> {
                        Preset preset = Preset.values()[which];
                        Settings.putString(Settings.DNS_PRESET, preset.stored);
                        CustomDns.clearCache();
                        if (preset == Preset.CUSTOM) editCustomDns(serverDescription[0]);
                        serverDescription[0].setText(dnsServerDescription());
                    })
                    .show();
        });
        serverDescription[0] = serverRow.description();
        options.addView(serverRow);

        TextView[] modeDescription = new TextView[1];
        ActionRow modeRow = ui.createActionRow(text("Способ", "Mode"), dnsModeDescription(), v -> {
            new ArsoundDialog(activity)
                    .setTitle(text("Способ", "Mode"))
                    .setItems(Mode.labels(), (dialog, which) -> {
                        Settings.putString(Settings.DNS_MODE, Mode.values()[which].stored);
                        CustomDns.clearCache();
                        modeDescription[0].setText(dnsModeDescription());
                    })
                    .show();
        });
        modeDescription[0] = modeRow.description();
        options.addView(modeRow);

        TextView[] testDescription = new TextView[1];
        ActionRow testRow = ui.createActionRow(text("Проверить", "Check"),
                text("Узнать адрес api-v2.soundcloud.com через выбранный сервер", "Resolve api-v2.soundcloud.com through the chosen server"),
                v -> {
                    testDescription[0].setText(text("Проверяю…", "Checking…"));
                    Utils.runOnBackgroundThread(() -> {
                        String result = CustomDns.test("api-v2.soundcloud.com");
                        Utils.runOnMainThread(() -> testDescription[0].setText(result != null ? result
                                : text("Сервер не ответил — будет использоваться обычный DNS", "No answer, the normal DNS is used")));
                    });
                });
        testDescription[0] = testRow.description();
        options.addView(testRow);
    }

    private String dnsServerDescription() {
        if (!CustomDns.PRESET_CUSTOM.equals(Settings.getDnsPreset())) {
            return CustomDns.XBOX_DOH_HOST + " — DoH " + CustomDns.XBOX_DOH + ", DNS " + String.join(", ", CustomDns.XBOX_SERVERS);
        }
        String doh = Settings.getCustomDohUrl();
        String servers = Settings.getCustomDnsServers();
        return text("Свой: ", "Custom: ") + (doh.isEmpty() ? "" : "DoH " + doh) + (servers.isEmpty() ? "" : " DNS " + servers)
                + text(" (нажмите, чтобы изменить)", " (tap to change)");
    }

    private String dnsModeDescription() {
        return Mode.of(Settings.getDnsMode()).label();
    }

    private void editCustomDns(TextView description) {
        LinearLayout form = ui.createColumn();
        form.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), 0);
        EditText doh = new EditText(activity);
        doh.setHint("https://example.com/dns-query");
        doh.setText(Settings.getCustomDohUrl());
        EditText servers = new EditText(activity);
        servers.setHint(text("IP через запятую: 1.1.1.1, 8.8.8.8", "IPs separated by commas: 1.1.1.1, 8.8.8.8"));
        servers.setText(Settings.getCustomDnsServers());
        form.addView(doh);
        form.addView(servers);
        new ArsoundDialog(activity)
                .setTitle(text("Свой DNS", "Custom DNS"))
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String url = doh.getText().toString().trim();
                    if (!url.isEmpty() && !url.startsWith("https://")) {
                        Toast.makeText(activity, text("Адрес DoH должен начинаться с https://", "DoH address must start with https://"),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    String list = servers.getText().toString().trim();
                    if (!list.isEmpty() && !list.matches("[0-9a-fA-F:.,\\s]+")) {
                        Toast.makeText(activity, text("Серверы — только IP-адреса", "Servers must be IP addresses"),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    Settings.putString(Settings.CUSTOM_DOH_URL, url);
                    Settings.putString(Settings.CUSTOM_DNS_SERVERS, list);
                    CustomDns.clearCache();
                    description.setText(dnsServerDescription());
                })
                .show();
    }
}
