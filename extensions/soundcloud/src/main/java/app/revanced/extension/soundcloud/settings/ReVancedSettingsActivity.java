package app.revanced.extension.soundcloud.settings;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * Settings screen of the ReVanced SoundCloud patches.
 * <p>
 * Built with plain Android views, but every visual detail (theme colors, fonts, text styles,
 * toggle row layout, icons, spacing) is taken from the SoundCloud resources by name,
 * so the screen follows the app look, including the dark theme.
 */
@SuppressWarnings("unused")
public final class ReVancedSettingsActivity extends Activity {
    private static final String CONSTRAINT_LAYOUT_CLASS = "androidx.constraintlayout.widget.ConstraintLayout";

    private static final String EXTRA_SCREEN = "arsound_screen";
    private static final String SCREEN_LOCAL_MUSIC = "local_music";
    private static final int REQUEST_IMPORT = 1;

    private LinearLayout localTrackList;

    private static final boolean RUSSIAN = "ru".equals(Locale.getDefault().getLanguage());

    private static String text(String russian, String english) {
        return RUSSIAN ? russian : english;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            String screen = getIntent().getStringExtra(EXTRA_SCREEN);
            setContentView(SCREEN_LOCAL_MUSIC.equals(screen) ? createLocalMusicContent()
                    : createContent());
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to create ReVanced settings screen", ex);
            finish();
        }
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(themeColor("themeColorSurface"));

        // targetSdk 35+ draws activities edge to edge, so keep the content out of the system bars.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom()
            );
            return insets.consumeSystemWindowInsets();
        });

        root.addView(createToolbar());

        ScrollView scrollView = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(list);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dimen("spacing_m"), dimen("spacing_s"), dimen("spacing_m"), dimen("spacing_l"));
        int logoId = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_icon");
        if (logoId != 0) {
            android.widget.ImageView logo = new android.widget.ImageView(this);
            logo.setImageResource(logoId);
            LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(36), dp(36));
            logoParams.rightMargin = dp(12);
            titleRow.addView(logo, logoParams);
        }
        titleRow.addView(createText("H1.Primary", "Arsound"));
        list.addView(titleRow);

        list.addView(createSubHeading(text("Сеть", "Network")));
        TextView network = createText("Body.Secondary", text("Проверяю, откуда приложение выходит в интернет…", "Checking the app network location…"));
        network.setPadding(dimen("spacing_m"), 0, dimen("spacing_m"), dimen("spacing_s"));
        list.addView(network);
        checkNetwork(network);
        addDnsOptions(list);
        list.addView(createToggleRow(
                text("Не выходить в сеть с российского IP", "Stay offline on a Russian IP"),
                text("Перед запросами к SoundCloud приложение проверяет страну своего IP через Cloudflare. "
                                + "Если IP российский или проверка не удалась, запросы к SoundCloud не отправляются, "
                                + "играют только скачанные и импортированные треки. Страна перепроверяется при смене сети. "
                                + "Геобаза Cloudflare может расходиться с базой SoundCloud.",
                        "Before contacting SoundCloud the app checks its IP country through Cloudflare. "
                                + "On a Russian IP, or if the check fails, no requests are sent to SoundCloud."),
                Settings.isRegionGuardEnabled(),
                (button, checked) -> Settings.putBoolean(Settings.REGION_GUARD, checked)
        ));
        list.addView(createActionRow(
                text("Проверить IP снова", "Check IP again"),
                text("Если сменили VPN или сеть, а SoundCloud всё ещё отключён.",
                        "If you switched a VPN or network and SoundCloud is still off."),
                v -> app.revanced.extension.soundcloud.network.RegionGuard.recheck(() -> {
                    String country = app.revanced.extension.soundcloud.network.RegionGuard.lastCountry();
                    Toast.makeText(this, country == null
                                    ? text("Не удалось проверить страну", "Could not check the country")
                                    : text("Страна IP: ", "IP country: ") + country,
                            Toast.LENGTH_SHORT).show();
                })
        ));

        list.addView(createToggleRow(
                text("Показывать статус сети", "Show network status"),
                text("Небольшая плашка сверху главного экрана, когда нет сети или SoundCloud отключён из-за российского IP. "
                                + "Нажатие проверяет сеть снова.",
                        "A small pill at the top of the main screen when there is no network or SoundCloud is off "
                                + "because of a Russian IP. Tap to check again."),
                Settings.isNetworkBannerEnabled(),
                (button, checked) -> Settings.putBoolean(Settings.NETWORK_BANNER, checked)
        ));
        list.addView(createToggleRow(
                text("Спрашивать перед проверкой устройства", "Ask before device check"),
                text("Защита SoundCloud от ботов (DataDome) иногда открывает белое окно проверки поверх приложения. "
                                + "Вместо этого появится вопрос: пройти проверку сейчас или позже.",
                        "SoundCloud's bot protection (DataDome) sometimes opens a white check screen over the app. "
                                + "Instead, you are asked whether to verify now or later."),
                Settings.isDataDomePromptEnabled(),
                (button, checked) -> Settings.putBoolean(Settings.DATADOME_PROMPT, checked)
        ));

        list.addView(createSubHeading(text("Конфиденциальность", "Privacy")));
        list.addView(createToggleRow(
                text("Телеметрия", "Telemetry"),
                text("Отправка статистики использования в SoundCloud. Изменение применится после перезапуска приложения.",
                        "Sends usage statistics to SoundCloud. Takes effect after restarting the app."),
                Settings.isTelemetryEnabled(),
                (button, checked) -> {
                    Settings.setTelemetryEnabled(checked);
                    Toast.makeText(this,
                            text("Перезапустите SoundCloud, чтобы применить", "Restart SoundCloud to apply"),
                            Toast.LENGTH_SHORT).show();
                }
        ));

        list.addView(createSubHeading(text("Интерфейс", "Interface")));
        list.addView(createToggleRow(
                text("Скрывать предложения подписки", "Hide subscription offers"),
                text("Сразу закрывает экран с предложением купить SoundCloud Go и Go+, "
                                + "в том числе с ошибкой «Oops… try again».",
                        "Closes the SoundCloud Go and Go+ offer screen as soon as it opens, "
                                + "including the \"Oops… try again\" error."),
                Settings.isHideSubscriptionOffersEnabled(),
                (button, checked) -> Settings.setHideSubscriptionOffersEnabled(checked)
        ));
        list.addView(createToggleRow(
                text("Скрыть вкладку Upgrade", "Hide Upgrade tab"),
                text("Убирает вкладку Upgrade из нижней панели, остальные вкладки занимают её место. "
                                + "Применяется после перезапуска.",
                        "Removes the Upgrade tab from the bottom bar, the other tabs take its space. "
                                + "Applies after a restart."),
                Settings.isHideUpgradeTabEnabled(),
                (button, checked) -> Settings.setHideUpgradeTabEnabled(checked)
        ));
        list.addView(createToggleRow(
                text("Сохранять плейлисты заранее", "Save playlists ahead"),
                text("В фоне сохраняет содержимое всех плейлистов и альбомов библиотеки — только текст: названия, "
                                + "исполнители, длительности. Без музыки и картинок, места почти не занимает. "
                                + "Плейлисты открываются сразу, в том числе без интернета.",
                        "Saves the contents of every playlist and album in the library in the background, as text only: "
                                + "titles, artists, durations. No audio or images, takes almost no space. "
                                + "Playlists open instantly, also offline."),
                Settings.isPlaylistPreloadEnabled(),
                (button, checked) -> Settings.putBoolean(Settings.PLAYLIST_PRELOAD, checked)
        ));
        list.addView(createToggleRow(
                text("Сначала сохранённое", "Offline first playlists"),
                text("Плейлисты и альбомы открываются сразу из памяти телефона, а обновляются в фоне. "
                                + "Помогает при плохом интернете.",
                        "Playlists and albums open instantly from the device and refresh in the background. "
                                + "Helps on a poor connection."),
                Settings.isOfflineFirstEnabled(),
                (button, checked) -> Settings.setOfflineFirstEnabled(checked)
        ));
        list.addView(createToggleRow(
                text("Играть скачанные из файла", "Play downloaded files"),
                text("Треки, скачанные Arsound в «Музыка/Arsound», играют из файла, а не из сети: "
                                + "работают без интернета и не тратят трафик. Действует для скачанных "
                                + "после этого обновления.",
                        "Tracks downloaded by Arsound to Music/Arsound play from the file instead of the network: "
                                + "they work offline and use no data. Applies to tracks downloaded after this update."),
                Settings.isPlayDownloadedFilesEnabled(),
                (button, checked) -> Settings.setPlayDownloadedFilesEnabled(checked)
        ));
        list.addView(createToggleRow(
                text("Повторять при сбое сети", "Retry on network errors"),
                text("Если трек оборвался из-за плохой связи, плеер сам повторит загрузку до трёх раз "
                                + "вместо ошибки «Track cannot be streamed».",
                        "If a track stops because of a poor connection, the player retries up to three times "
                                + "instead of showing \"Track cannot be streamed\"."),
                Settings.isPlaybackRetryEnabled(),
                (button, checked) -> Settings.setPlaybackRetryEnabled(checked)
        ));
        list.addView(createToggleRow(
                text("Блокировать рекламу в плеере", "Block playback advertisements"),
                text("Не запрашивает аудио- и видеорекламу между треками и рекламные плашки "
                                + "в плеере, ленте, библиотеке, плейлистах и профилях. "
                                + "После изменения перезапустите SoundCloud.",
                        "Prevents audio and video advertisements between tracks and banner ads "
                                + "in the player, feed, library, playlists and profiles. "
                                + "Restart SoundCloud after changing this option."),
                Settings.isBlockPlaybackAdsEnabled(),
                (button, checked) -> {
                    Settings.setBlockPlaybackAdsEnabled(checked);
                    Toast.makeText(this,
                            text("Перезапустите SoundCloud, чтобы применить", "Restart SoundCloud to apply"),
                            Toast.LENGTH_SHORT).show();
                }
        ));

        list.addView(createSubHeading(text("Рекомендации", "Recommendations")));
        LinearLayout duplicateOptions = new LinearLayout(this);
        duplicateOptions.setOrientation(LinearLayout.VERTICAL);
        list.addView(createToggleRow(
                text("Скрывать дубликаты", "Hide duplicates"),
                text("Один и тот же трек, перезалитый разными людьми, показывается в рекомендациях на главной "
                                + "и в автовоспроизведении только один раз. Совпадение — по названию и длительности (±2 с). "
                                + "Лайки, плейлисты и профили не трогаются.",
                        "The same song re-uploaded by different users appears once in home recommendations and autoplay. "
                                + "Matched by title and duration (±2 s)."),
                Settings.isDuplicateFilterEnabled(),
                (button, checked) -> {
                    Settings.putBoolean(Settings.DUPLICATE_FILTER, checked);
                    duplicateOptions.setVisibility(checked ? View.VISIBLE : View.GONE);
                }
        ));
        duplicateOptions.setVisibility(Settings.isDuplicateFilterEnabled() ? View.VISIBLE : View.GONE);
        duplicateOptions.addView(createToggleRow(
                text("Считать slowed, sped up и ремиксы тем же треком", "Treat slowed, sped up and remixes as the same song"),
                text("Иначе такие версии остаются отдельными треками.", "Otherwise these versions stay separate."),
                Settings.isMergeEditedVersions(),
                (button, checked) -> Settings.putBoolean(Settings.MERGE_EDITED_VERSIONS, checked)
        ));
        list.addView(duplicateOptions);

        list.addView(createSubHeading(text("Энергосбережение", "Power saving")));
        list.addView(createToggleRow(
                text("Экономия батареи", "Battery saving"),
                text("Значок новых сообщений обновляется раз в 5 минут вместо каждых 30 секунд, а встроенные "
                                + "сторонние SDK (Statsig, MoEngage) не отправляют фоновые отчёты каждые несколько секунд: "
                                + "меньше просыпается радиомодуль. Опрос сообщений меняется после перезапуска.",
                        "The new messages badge updates every 5 minutes instead of every 30 seconds, "
                                + "so the radio wakes up less. Applies after a restart."),
                Settings.isPowerSavingEnabled(),
                (button, checked) -> Settings.setPowerSavingEnabled(checked)
        ));

        list.addView(createSubHeading(text("Локальная музыка", "Local music")));
        LinearLayout savedOptions = new LinearLayout(this);
        savedOptions.setOrientation(LinearLayout.VERTICAL);
        list.addView(createToggleRow(
                text("Плейлист «Импортированные»", "\"Imported\" playlist"),
                text("В библиотеке SoundCloud появится приватный плейлист со всеми "
                                + "импортированными файлами. Треки в нём есть только на этом телефоне. "
                                + "Плейлист нельзя удалить: пока функция включена, он создаётся снова. "
                                + "Применится после перезапуска.",
                        "A private playlist with all imported files appears in the "
                                + "SoundCloud library. Its tracks exist only on this phone. Applies after a restart."),
                Settings.isSavedPlaylistEnabled(),
                (button, checked) -> {
                    Settings.putBoolean(Settings.SAVED_PLAYLIST, checked);
                    savedOptions.setVisibility(checked ? View.VISIBLE : View.GONE);
                }
        ));
        savedOptions.setVisibility(Settings.isSavedPlaylistEnabled() ? View.VISIBLE : View.GONE);
        savedOptions.addView(createToggleRow(
                text("Скрыть этот плейлист", "Hide this playlist"),
                text("Убирает плейлист из библиотеки, не выключая функцию.", "Removes the playlist from the library without turning the feature off."),
                Settings.isSavedPlaylistHidden(),
                (button, checked) -> Settings.putBoolean(Settings.SAVED_PLAYLIST_HIDDEN, checked)
        ));
        list.addView(savedOptions);
        list.addView(createToggleRow(
                text("Свой порядок плейлистов", "Custom playlist order"),
                text("Долгое нажатие на плейлист в «Библиотека → Плейлисты» включает перестановку: плейлисты "
                                + "покачиваются, зажатый можно перетащить. Касание выключает режим. Порядок хранится на телефоне.",
                        "Long press a playlist in Library → Playlists to rearrange: playlists wiggle and the pressed one "
                                + "can be dragged. A tap ends it. The order is kept on this phone."),
                Settings.isPlaylistOrderEnabled(),
                (button, checked) -> Settings.putBoolean(Settings.PLAYLIST_ORDER, checked)
        ));
        list.addView(createActionRow(
                text("Сбросить порядок плейлистов", "Reset playlist order"),
                text("Вернуть сортировку SoundCloud.", "Go back to SoundCloud's sorting."),
                v -> {
                    app.revanced.extension.soundcloud.local.PlaylistOrder.reset();
                    Toast.makeText(this, text("Порядок сброшен", "Order reset"), Toast.LENGTH_SHORT).show();
                }
        ));
        list.addView(createActionRow(
                text("Импортированные файлы", "Imported files"),
                text("Импорт аудиофайлов с телефона и список импортированного. Файлы появляются в плейлисте "
                                + "«Импортированные».",
                        "Import audio files from the phone and see what is imported. Files appear in the "
                                + "\"Imported\" playlist."),
                v -> startActivity(new android.content.Intent(this, ReVancedSettingsActivity.class)
                        .putExtra(EXTRA_SCREEN, SCREEN_LOCAL_MUSIC))
        ));

        list.addView(createSubHeading(text("Данные", "Data")));
        list.addView(createActionRow(
                text("Сбросить данные SoundCloud", "Reset SoundCloud data"),
                text("Удалит кэш, базу треков и настройки SoundCloud, как «Очистить данные» в Android. "
                                + "Вход в аккаунт, настройки Arsound и список скачанных треков сохранятся.",
                        "Removes the SoundCloud cache, track database and app settings, like \"Clear data\" in Android. "
                                + "Your login, Arsound settings and downloaded tracks list are kept."),
                v -> confirmReset()
        ));

        list.addView(createSubHeading(text("Обновления", "Updates")));
        list.addView(createToggleRow(
                text("Проверять при запуске", "Check on launch"),
                text("При каждом запуске в фоне смотрит, вышла ли новая версия Arsound на GitHub.",
                        "Checks GitHub for a new Arsound version in the background on every launch."),
                Settings.isUpdateCheckEnabled(),
                (button, checked) -> Settings.setUpdateCheckEnabled(checked)
        ));
        list.addView(createActionRow(
                text("Проверить обновления", "Check for updates"),
                text("Версия " + UpdateChecker.VERSION, "Version " + UpdateChecker.VERSION),
                v -> UpdateChecker.check((release, failed) -> {
                    if (isFinishing()) return;
                    if (release != null) {
                        UpdateChecker.showUpdateSheet(this, release);
                    } else {
                        Toast.makeText(this, failed
                                        ? text("Не удалось проверить обновления", "Could not check for updates")
                                        : text("У вас последняя версия", "You have the latest version"),
                                Toast.LENGTH_SHORT).show();
                    }
                })
        ));
        list.addView(createActionRow(
                text("Arsound на GitHub", "Arsound on GitHub"),
                UpdateChecker.REPOSITORY_URL.replace("https://", ""),
                v -> UpdateChecker.openUrl(this, UpdateChecker.REPOSITORY_URL)
        ));

        list.addView(createSubHeading(text("Для разработчика", "Developer")));
        LinearLayout developerOptions = new LinearLayout(this);
        developerOptions.setOrientation(LinearLayout.VERTICAL);
        list.addView(createToggleRow(
                text("Настройки для разработчика", "Developer options"),
                text("Показывает инструменты для проверки и отладки. Обычному пользователю не нужны.",
                        "Shows testing and debugging tools. Not needed for everyday use."),
                Settings.isDeveloperModeEnabled(),
                (button, checked) -> {
                    Settings.setDeveloperModeEnabled(checked);
                    developerOptions.setVisibility(checked ? View.VISIBLE : View.GONE);
                }
        ));
        developerOptions.setVisibility(Settings.isDeveloperModeEnabled() ? View.VISIBLE : View.GONE);
        list.addView(developerOptions);
        addDeveloperOptions(developerOptions);

        return root;
    }

    private void addDnsOptions(LinearLayout list) {
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        list.addView(createToggleRow(
                text("Свой DNS", "Custom DNS"),
                text("Приложение узнаёт адреса серверов через выбранный DNS, а не через DNS провайдера. "
                                + "Помогает, если провайдер режет или подменяет SoundCloud. Если сервер не отвечает, "
                                + "используется обычный DNS.",
                        "The app resolves server addresses through the chosen DNS instead of the provider DNS. "
                                + "If the server does not answer, the normal DNS is used."),
                Settings.isCustomDnsEnabled(),
                (button, checked) -> {
                    Settings.putBoolean(Settings.CUSTOM_DNS, checked);
                    app.revanced.extension.soundcloud.network.CustomDns.clearCache();
                    options.setVisibility(checked ? View.VISIBLE : View.GONE);
                }
        ));
        options.setVisibility(Settings.isCustomDnsEnabled() ? View.VISIBLE : View.GONE);
        list.addView(options);

        TextView[] serverDescription = new TextView[1];
        View serverRow = createActionRow(text("Сервер", "Server"), dnsServerDescription(), v -> {
            String[] presets = {"xbox-dns.ru", text("Свой сервер", "Custom server")};
            new android.app.AlertDialog.Builder(this)
                    .setTitle(text("Сервер DNS", "DNS server"))
                    .setItems(presets, (dialog, which) -> {
                        Settings.putString(Settings.DNS_PRESET, which == 0 ? app.revanced.extension.soundcloud.network.CustomDns.PRESET_XBOX : app.revanced.extension.soundcloud.network.CustomDns.PRESET_CUSTOM);
                        app.revanced.extension.soundcloud.network.CustomDns.clearCache();
                        if (which == 1) editCustomDns(serverDescription[0]);
                        serverDescription[0].setText(dnsServerDescription());
                    })
                    .show();
        });
        serverDescription[0] = (TextView) ((ViewGroup) serverRow).getChildAt(1);
        options.addView(serverRow);

        TextView[] modeDescription = new TextView[1];
        View modeRow = createActionRow(text("Способ", "Mode"), dnsModeDescription(), v -> {
            String[] modes = {text("Авто: сначала DoH, потом обычный", "Auto: DoH first, then plain"),
                    text("Только DNS-over-HTTPS", "DNS-over-HTTPS only"),
                    text("Только обычный DNS", "Plain DNS only")};
            new android.app.AlertDialog.Builder(this)
                    .setTitle(text("Способ", "Mode"))
                    .setItems(modes, (dialog, which) -> {
                        Settings.putString(Settings.DNS_MODE, which == 0 ? app.revanced.extension.soundcloud.network.CustomDns.MODE_AUTO
                                : which == 1 ? app.revanced.extension.soundcloud.network.CustomDns.MODE_DOH : app.revanced.extension.soundcloud.network.CustomDns.MODE_PLAIN);
                        app.revanced.extension.soundcloud.network.CustomDns.clearCache();
                        modeDescription[0].setText(dnsModeDescription());
                    })
                    .show();
        });
        modeDescription[0] = (TextView) ((ViewGroup) modeRow).getChildAt(1);
        options.addView(modeRow);

        TextView[] testDescription = new TextView[1];
        View testRow = createActionRow(text("Проверить", "Check"),
                text("Узнать адрес api-v2.soundcloud.com через выбранный сервер", "Resolve api-v2.soundcloud.com through the chosen server"),
                v -> {
                    testDescription[0].setText(text("Проверяю…", "Checking…"));
                    Utils.runOnBackgroundThread(() -> {
                        String result = app.revanced.extension.soundcloud.network.CustomDns.test("api-v2.soundcloud.com");
                        Utils.runOnMainThread(() -> testDescription[0].setText(result != null ? result
                                : text("Сервер не ответил — будет использоваться обычный DNS", "No answer, the normal DNS is used")));
                    });
                });
        testDescription[0] = (TextView) ((ViewGroup) testRow).getChildAt(1);
        options.addView(testRow);
    }

    private String dnsServerDescription() {
        if (!app.revanced.extension.soundcloud.network.CustomDns.PRESET_CUSTOM.equals(Settings.getDnsPreset())) {
            return "xbox-dns.ru — DoH " + app.revanced.extension.soundcloud.network.CustomDns.XBOX_DOH + ", DNS 111.88.96.50, 111.88.96.51";
        }
        String doh = Settings.getCustomDohUrl();
        String servers = Settings.getCustomDnsServers();
        return text("Свой: ", "Custom: ") + (doh.isEmpty() ? "" : "DoH " + doh) + (servers.isEmpty() ? "" : " DNS " + servers)
                + text(" (нажмите, чтобы изменить)", " (tap to change)");
    }

    private String dnsModeDescription() {
        switch (Settings.getDnsMode()) {
            case "doh":
                return text("Только DNS-over-HTTPS", "DNS-over-HTTPS only");
            case "plain":
                return text("Только обычный DNS", "Plain DNS only");
            default:
                return text("Авто: сначала DoH, потом обычный", "Auto: DoH first, then plain");
        }
    }

    private void editCustomDns(TextView description) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dimen("spacing_m"), 0, dimen("spacing_m"), 0);
        android.widget.EditText doh = new android.widget.EditText(this);
        doh.setHint("https://example.com/dns-query");
        doh.setText(Settings.getCustomDohUrl());
        android.widget.EditText servers = new android.widget.EditText(this);
        servers.setHint(text("IP через запятую: 1.1.1.1, 8.8.8.8", "IPs separated by commas: 1.1.1.1, 8.8.8.8"));
        servers.setText(Settings.getCustomDnsServers());
        form.addView(doh);
        form.addView(servers);
        new android.app.AlertDialog.Builder(this)
                .setTitle(text("Свой DNS", "Custom DNS"))
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String url = doh.getText().toString().trim();
                    if (!url.isEmpty() && !url.startsWith("https://")) {
                        Toast.makeText(this, text("Адрес DoH должен начинаться с https://", "DoH address must start with https://"),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    String list = servers.getText().toString().trim();
                    if (!list.isEmpty() && !list.matches("[0-9a-fA-F:.,\\s]+")) {
                        Toast.makeText(this, text("Серверы — только IP-адреса", "Servers must be IP addresses"),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    Settings.putString(Settings.CUSTOM_DOH_URL, url);
                    Settings.putString(Settings.CUSTOM_DNS_SERVERS, list);
                    app.revanced.extension.soundcloud.network.CustomDns.clearCache();
                    description.setText(dnsServerDescription());
                })
                .show();
    }

    private static final int[] NETWORK_DELAYS = {0, 3, 10, 20, 40};

    private void addDeveloperOptions(LinearLayout container) {
        TextView[] delayDescription = new TextView[1];
        View delayRow = createActionRow(
                text("Имитация плохой сети", "Simulate a poor connection"),
                networkDelayDescription(),
                v -> {
                    int current = Settings.isDeveloperModeEnabled()
                            ? Settings.getDeveloperNetworkDelaySeconds() : 0;
                    int next = NETWORK_DELAYS[0];
                    for (int i = 0; i < NETWORK_DELAYS.length; i++) {
                        if (NETWORK_DELAYS[i] == current) {
                            next = NETWORK_DELAYS[(i + 1) % NETWORK_DELAYS.length];
                            break;
                        }
                    }
                    Settings.setDeveloperNetworkDelaySeconds(next);
                    delayDescription[0].setText(networkDelayDescription());
                }
        );
        delayDescription[0] = (TextView) ((ViewGroup) delayRow).getChildAt(1);
        container.addView(delayRow);
    }

    private String networkDelayDescription() {
        int delay = Settings.getDeveloperNetworkDelaySeconds();
        String state = delay == 0
                ? text("выключено", "off")
                : text("задержка " + delay + " с на каждый запрос", delay + " s delay per request");
        return text("Нажмите, чтобы переключить: ", "Tap to change: ") + state + ". "
                + text("Замедляет все запросы SoundCloud, чтобы проверить работу на плохом интернете. "
                        + "Действует сразу.",
                "Slows down every SoundCloud request to test behavior on a poor connection. Applies immediately.");
    }

    private View createLocalMusicContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(themeColor("themeColorSurface"));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom()
            );
            return insets.consumeSystemWindowInsets();
        });
        root.addView(createToolbar());

        ScrollView scrollView = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(list);
        root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView title = createText("H1.Primary", text("Мои файлы", "My files"));
        title.setPadding(dimen("spacing_m"), dimen("spacing_s"), dimen("spacing_m"), dimen("spacing_l"));
        list.addView(title);

        list.addView(createActionRow(
                text("Импортировать файлы", "Import files"),
                text("MP3, M4A, FLAC, OGG, WAV. Файлы копируются в память приложения.",
                        "MP3, M4A, FLAC, OGG, WAV. Files are copied into the app storage."),
                v -> {
                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            .setType("audio/*")
                            .putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(intent, REQUEST_IMPORT);
                }
        ));
        list.addView(createSubHeading(text("Треки", "Tracks")));
        localTrackList = new LinearLayout(this);
        localTrackList.setOrientation(LinearLayout.VERTICAL);
        list.addView(localTrackList);
        reloadLocalTracks();

        return root;
    }

    private java.util.List<app.revanced.extension.soundcloud.local.LocalMusic.Track> localTracks = new java.util.ArrayList<>();

    private void reloadLocalTracks() {
        Utils.runOnBackgroundThread(() -> {
            java.util.List<app.revanced.extension.soundcloud.local.LocalMusic.Track> tracks =
                    app.revanced.extension.soundcloud.local.LocalMusic.getTracks(this);
            Utils.runOnMainThread(() -> showLocalTracks(tracks));
        });
    }

    private void showLocalTracks(java.util.List<app.revanced.extension.soundcloud.local.LocalMusic.Track> tracks) {
        localTracks = tracks;
        localTrackList.removeAllViews();
        if (tracks.isEmpty()) {
            TextView empty = createText("Body.Secondary", text("Пока пусто. Нажмите «Импортировать файлы».",
                    "Nothing here yet. Tap \"Import files\"."));
            empty.setPadding(dimen("spacing_m"), 0, dimen("spacing_m"), dimen("spacing_s"));
            localTrackList.addView(empty);
            return;
        }

        for (int i = 0; i < tracks.size(); i++) {
            app.revanced.extension.soundcloud.local.LocalMusic.Track track = tracks.get(i);
            int index = i;
            long seconds = track.durationMs / 1000;
            String details = (track.artist.isEmpty() ? "" : track.artist + " · ")
                    + String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
            View row = createActionRow(track.title, details, v -> playLocal(index, false));
            row.setOnLongClickListener(v -> {
                new android.app.AlertDialog.Builder(this)
                        .setTitle(track.title)
                        .setNeutralButton(text("В плейлист…", "To playlist…"), (dialog, which) ->
                                app.revanced.extension.soundcloud.local.LocalAdditions.pickPlaylist(this, app.revanced.extension.soundcloud.local.LocalAdditions.fileEntry(track.file)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(text("Удалить файл", "Delete file"), (dialog, which) -> {
                            app.revanced.extension.soundcloud.local.LocalMusic.delete(track);
                            reloadLocalTracks();
                        })
                        .show();
                return true;
            });
            localTrackList.addView(row);
        }
    }

    private void playLocal(int index, boolean shuffle) {
        java.util.List<java.io.File> files = new java.util.ArrayList<>();
        for (app.revanced.extension.soundcloud.local.LocalMusic.Track track : localTracks) files.add(track.file);
        if (files.isEmpty()) return;

        if (app.revanced.extension.soundcloud.local.LocalMusic.play(files, index, shuffle)) {
            finish();
        } else {
            Toast.makeText(this, text("Плеер ещё не готов. Откройте SoundCloud и попробуйте снова.",
                    "The player is not ready. Open SoundCloud and try again."), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null) return;

        java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        Toast.makeText(this, text("Импортирую…", "Importing…"), Toast.LENGTH_SHORT).show();
        Utils.runOnBackgroundThread(() -> {
            int count = app.revanced.extension.soundcloud.local.LocalMusic.importFiles(this, uris);
            Utils.runOnMainThread(() -> {
                Toast.makeText(this, text("Импортировано: " + count, "Imported: " + count), Toast.LENGTH_SHORT).show();
                reloadLocalTracks();
            });
        });
    }

    private void confirmReset() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(text("Сбросить данные?", "Reset data?"))
                .setMessage(text("SoundCloud перезапустится и заново загрузит библиотеку. "
                                + "Вход, настройки Arsound и скачанные треки останутся.",
                        "SoundCloud restarts and reloads your library. "
                                + "Login, Arsound settings and downloaded tracks are kept."))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(text("Сбросить", "Reset"), (dialog, which) -> {
                    DataReset.resetKeepingLogin(this);
                    Utils.restartApp(this);
                })
                .show();
    }

    private View createActionRow(String title, String description, View.OnClickListener listener) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundResource(themeAttribute(android.R.attr.selectableItemBackground));
        container.setPadding(0, dimen("spacing_s"), 0, dimen("spacing_s"));

        TextView titleView = createText("H4.Primary", title);
        titleView.setPadding(dimen("spacing_m"), 0, dimen("spacing_m"), dp(4));
        container.addView(titleView);

        TextView descriptionView = createText("Body.Secondary", description);
        descriptionView.setPadding(dimen("spacing_m"), 0, dp(72), 0);
        container.addView(descriptionView);

        container.setOnClickListener(listener);
        return container;
    }

    /**
     * Shows the IP address and country this app uses, to diagnose region blocks caused by VPN routing.
     */
    private void checkNetwork(TextView view) {
        Utils.runOnBackgroundThread(() -> {
            String result;
            try {
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                        new java.net.URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection();
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(8_000);

                String ip = "?";
                String country = "?";
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(connection.getInputStream()))) {
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

    private View createToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setMinimumHeight(dp(56));
        toolbar.setPadding(dp(4), 0, dimen("spacing_m"), 0);

        ImageButton back = new ImageButton(this);
        back.setImageResource(Utils.getResourceIdentifier(ResourceType.DRAWABLE, "ic_actions_back_primary"));
        back.setBackgroundResource(themeAttribute(android.R.attr.selectableItemBackgroundBorderless));
        back.setContentDescription(text("Назад", "Back"));
        back.setOnClickListener(v -> finish());
        int size = dp(48);
        toolbar.addView(back, new LinearLayout.LayoutParams(size, size));

        return toolbar;
    }

    private View createSubHeading(String label) {
        TextView heading = createText("H4.Secondary", label);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setMinHeight(dimen("action_list_sub_heading_height"));
        heading.setPadding(dimen("spacing_m"), 0, dimen("spacing_m"), 0);
        return heading;
    }

    private View createToggleRow(String title, String description, boolean checked,
                                 CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundResource(themeAttribute(android.R.attr.selectableItemBackground));

        // SoundCloud's own toggle row layout: title on the left, SoundCloud switch on the right.
        ViewGroup row = createConstraintLayout();
        row.setMinimumHeight(dimen("action_list_default_height"));
        LayoutInflater.from(this).inflate(
                Utils.getResourceIdentifier(ResourceType.LAYOUT, "layout_action_list_toggle"), row, true);

        TextView titleView = row.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "action_list_title"));
        titleView.setText(title);

        CompoundButton toggle = row.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "action_list_switch_button"));
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);

        container.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView descriptionView = createText("Body.Secondary", description);
        descriptionView.setPadding(dimen("spacing_m"), 0, dp(72), dimen("spacing_s"));
        container.addView(descriptionView);

        container.setOnClickListener(v -> toggle.toggle());
        return container;
    }

    private ViewGroup createConstraintLayout() {
        try {
            return (ViewGroup) Class.forName(CONSTRAINT_LAYOUT_CLASS)
                    .getConstructor(Context.class)
                    .newInstance(this);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("ConstraintLayout not found in SoundCloud", ex);
        }
    }

    private TextView createText(String styleName, String value) {
        TextView view = new TextView(this);
        int style = Utils.getResourceIdentifier(ResourceType.STYLE, styleName);
        if (style != 0) {
            view.setTextAppearance(style);
        } else {
            view.setTextColor(themeColor("themeColorPrimary"));
        }
        view.setText(value);
        return view;
    }

    private int themeColor(String attributeName) {
        int attribute = Utils.getResourceIdentifier(ResourceType.ATTR, attributeName);
        TypedArray array = obtainStyledAttributes(new int[]{attribute});
        try {
            return array.getColor(0, 0);
        } finally {
            array.recycle();
        }
    }

    private int themeAttribute(int attribute) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        return value.resourceId;
    }

    private int dimen(String name) {
        int id = Utils.getResourceIdentifier(ResourceType.DIMEN, name);
        return id == 0 ? dp(16) : getResources().getDimensionPixelSize(id);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
