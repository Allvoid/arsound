package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.region.RegionHintSettings;

/** The IP country block, custom DNS, the network status pill and the device check prompt. */
final class NetworkScreen extends SectionScreen {
    static final String ID = "network";

    NetworkScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Сеть", "Network");
    }

    @Override
    String description() {
        return text("Блок по стране IP, свой DNS, статус сети, проверка устройства", "IP country block, custom DNS, network status, device check");
    }

    @Override
    String iconKey() {
        return "network";
    }

    @Override
    String keywords() {
        return text("интернет vpn прокси dns ip страна блокировка", "internet vpn proxy dns ip country block");
    }

    @Override
    String summary() {
        return text("DNS: ", "DNS: ") + (BoolSettings.CUSTOM_DNS.get() ? text("свой", "custom") : text("обычный", "normal"))
                + text(" · блок по стране: ", " · country block: ")
                + (BoolSettings.REGION_GUARD.get() ? text("вкл", "on") : text("выкл", "off"));
    }

    @Override
    void fill(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Состояние", "State")));
        ActionRow state = ui.createActionRow(text("Откуда приложение выходит в сеть", "Where the app goes online from"),
                text("Проверяю, откуда приложение выходит в интернет…", "Checking the app network location…"),
                null);
        state.icon("network");
        list.addView(state);
        checkNetwork(state.description());
        state.setOnClickListener(v -> checkNetwork(state.description()));

        list.addView(ui.createSubHeading("DNS"));
        new DnsRows(ui).addTo(list);

        list.addView(ui.createSubHeading(text("Блок по стране IP", "IP country block")));
        SubRows regionOptions = ui.createSubRows();
        list.addView(ui.createToggleRow(
                text("Не выходить в сеть с IP выбранных стран", "Stay offline on IPs of chosen countries"),
                text("Если приложение выходит в интернет с IP одной из отмеченных ниже стран, оно не обращается ни к "
                                + "SoundCloud, ни к поиску Arsound. Играют скачанные и импортированные треки. Страна "
                                + "определяется через Cloudflare; если проверка не прошла, запросы ждут следующей.",
                        "On an IP of one of the countries ticked below the app contacts neither SoundCloud nor the "
                                + "Arsound search; downloaded and imported tracks still play. The country is checked "
                                + "through Cloudflare; after a failed check requests wait for the next one."),
                BoolSettings.REGION_GUARD,
                regionOptions
        ).icon("region").brief(text("Не обращаться к SoundCloud с IP отмеченных стран.",
                "Do not contact SoundCloud from an IP of the ticked countries.")));
        new RegionGuardRows(ui).addTo(regionOptions);
        regionOptions.addView(ui.createToggleRow(
                text("Проверять при смене сети и VPN", "Check when the network or VPN changes"),
                text("Страна проверяется заново, как только телефон сменил сеть или включил либо выключил VPN. "
                                + "Если выключить, приложение помнит прошлую страну до проверки вручную: после отключения "
                                + "VPN запросы могут уйти с российского IP.",
                        "The country is checked again as soon as the phone changes the network or turns a VPN on or "
                                + "off. When off, the app keeps the last country until you check by hand: after a VPN "
                                + "is turned off, requests may go from a Russian IP."),
                BoolSettings.REGION_RECHECK_ON_CHANGE
        ).brief(text("Страна проверяется заново при смене сети и VPN.", "The country is checked again when the network or VPN changes.")));
        regionOptions.addView(ui.createToggleRow(
                text("Проверять раз в 30 секунд", "Check every 30 seconds"),
                text("Пока идут запросы, страна перепроверяется в фоне раз в 30 секунд: так заметен VPN, который "
                                + "отвалился без смены сети. Без неё — меньше запросов к Cloudflare.",
                        "While requests go, the country is checked again in the background every 30 seconds, which "
                                + "catches a VPN that dropped without a network change. Off means fewer requests to Cloudflare."),
                BoolSettings.REGION_RECHECK_PERIODIC
        ).brief(text("Ловит VPN, который отвалился без смены сети.", "Catches a VPN that dropped without a network change.")));
        regionOptions.addView(ui.createActionRow(
                text("Проверить IP снова", "Check IP again"),
                text("Если включили VPN или сменили сеть, а SoundCloud всё ещё не работает.",
                        "If you turned on a VPN or changed the network and SoundCloud still does not work."),
                v -> RegionGuard.recheck(() -> {
                    String country = RegionGuard.lastCountry();
                    Toast.makeText(activity, country == null
                                    ? text("Не удалось проверить страну", "Could not check the country")
                                    : text("Страна IP: ", "IP country: ") + country,
                            Toast.LENGTH_SHORT).show();
                })
        ).chevron());
        regionOptions.addView(ui.createToggleRow(RegionHintSettings.title(), RegionHintSettings.description(),
                BoolSettings.REGION_HINT));
        list.addView(regionOptions);

        list.addView(ui.createSubHeading(text("Уведомления сети", "Network notices")));
        list.addView(ui.createToggleRow(
                text("Показывать статус сети", "Show network status"),
                text("Маленький значок перечёркнутого интернета внизу справа, над плеером, когда нет сети или "
                                + "SoundCloud отключён из-за IP выбранной страны. Нажмите — сеть проверится снова; "
                                + "удерживайте — покажет, в чём дело.",
                        "A small crossed-out globe at the bottom right, above the player, when there is no connection "
                                + "or SoundCloud is off because of an IP of a chosen country. Tap it to check again, "
                                + "hold it to see why."),
                BoolSettings.NETWORK_BANNER
        ).icon("network").brief(text("Значок над плеером, когда нет сети или SoundCloud отключён.",
                "A badge above the player when there is no connection or SoundCloud is off.")));
        list.addView(ui.createToggleRow(
                text("Спрашивать перед проверкой устройства", "Ask before device check"),
                text("SoundCloud иногда проверяет, не бот ли вы, и открывает белое окно поверх приложения. "
                                + "Вместо этого появится вопрос: пройти проверку сейчас или позже.",
                        "SoundCloud sometimes checks that you are not a bot and opens a white screen over the app. "
                                + "Instead, you are asked whether to do it now or later."),
                BoolSettings.DATADOME_PROMPT
        ).icon("check").brief(text("Вместо белого окна проверки — вопрос: сейчас или позже.",
                "Instead of a white check screen, a question: now or later.")));
    }

    /**
     * Shows the IP address and country this app uses, to diagnose region blocks caused by VPN routing.
     */
    private void checkNetwork(TextView view) {
        ui.fillInBackground(() -> {
            String result;
            try {
                HttpURLConnection connection = (HttpURLConnection)
                        new URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection();
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(8_000);

                String ip = "?";
                String country = "?";
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("ip=")) ip = line.substring(3);
                        if (line.startsWith("loc=")) country = line.substring(4);
                    }
                }
                result = text("IP приложения: " + ip + ", страна: " + country, "App IP: " + ip + ", country: " + country);
            } catch (Exception ex) {
                result = text("Не удалось проверить сеть", "Could not check the network");
            }

            String text = result;
            Utils.runOnMainThread(() -> view.setText(text));
        });
    }
}
