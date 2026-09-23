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
            setContentView(screen == null ? createContent()
                    : SCREEN_LOCAL_MUSIC.equals(screen) ? createLocalMusicContent()
                    : createSection(screen));
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to create ReVanced settings screen", ex);
            finish();
        }
    }

    private static final String SCREEN_NETWORK = "network";
    private static final String SCREEN_PLAYBACK = "playback";
    private static final String SCREEN_ADS = "ads";
    private static final String SCREEN_RECOMMENDATIONS = "recommendations";
    private static final String SCREEN_MUSIC = "music";
    private static final String SCREEN_PRIVACY = "privacy";
    private static final String SCREEN_UPDATES = "updates";
    private static final String SCREEN_DEVELOPER = "developer";
    private static final String SCREEN_ACCOUNT = "account";

    /** A screen with the toolbar and a scrolling list. Returns the root; the list is the last child of the scroll view. */
    private LinearLayout createScreen(LinearLayout[] listOut) {
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
        listOut[0] = list;
        return root;
    }

    private View createTitle(String title, boolean withLogo) {
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dimen("spacing_m"), dimen("spacing_s"), dimen("spacing_m"), dimen("spacing_l"));
        int logoId = withLogo ? Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_icon") : 0;
        if (logoId != 0) {
            android.widget.ImageView logo = new android.widget.ImageView(this);
            logo.setImageResource(logoId);
            LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(36), dp(36));
            logoParams.rightMargin = dp(12);
            titleRow.addView(logo, logoParams);
        }
        titleRow.addView(createText("H1.Primary", title));
        return titleRow;
    }

    private View openScreenRow(String title, String description, String screen) {
        return createActionRow(title, description, v -> startActivity(
                new android.content.Intent(this, ReVancedSettingsActivity.class).putExtra(EXTRA_SCREEN, screen)));
    }

    /** The main screen: one row per section, each section opens as its own screen. */
    private View createContent() {
        LinearLayout[] holder = new LinearLayout[1];
        LinearLayout root = createScreen(holder);
        LinearLayout list = holder[0];
        list.addView(createTitle("Arsound", true));

        list.addView(openScreenRow(text("Сеть", "Network"),
                text("Российский IP, свой DNS, статус сети, проверка устройства", "Russian IP, custom DNS, network status, device check"),
                SCREEN_NETWORK));
        list.addView(openScreenRow(text("Офлайн и воспроизведение", "Offline and playback"),
                text("Плейлисты без интернета, повтор при обрыве связи", "Playlists without a connection, retry on dropped connection"),
                SCREEN_PLAYBACK));
        list.addView(openScreenRow(text("Реклама и подписка", "Ads and subscription"),
                text("Реклама, предложения Go и Go+, вкладка Upgrade", "Ads, Go and Go+ offers, Upgrade tab"),
                SCREEN_ADS));
        list.addView(openScreenRow(text("Рекомендации", "Recommendations"),
                text("Дубликаты треков на главной и в автовоспроизведении", "Duplicate tracks on the home screen and in autoplay"),
                SCREEN_RECOMMENDATIONS));
        list.addView(openScreenRow(text("Своя музыка", "Your music"),
                text("Импорт файлов, плейлист «Импортированные», свой порядок", "Import files, \"Imported\" playlist, custom order"),
                SCREEN_MUSIC));
        list.addView(openScreenRow(text("Батарея и конфиденциальность", "Battery and privacy"),
                text("Экономия батареи, телеметрия", "Battery saving, telemetry"),
                SCREEN_PRIVACY));
        list.addView(openScreenRow(text("Обновления и данные", "Updates and data"),
                text("Версия " + UpdateChecker.VERSION + ", проверка обновлений, сброс данных SoundCloud",
                        "Version " + UpdateChecker.VERSION + ", update check, SoundCloud data reset"),
                SCREEN_UPDATES));
        list.addView(openScreenRow(text("Аккаунт", "Account"),
                text("Аккаунт YouTube Music для поиска Arsound — по желанию", "YouTube Music account for the Arsound search, optional"),
                SCREEN_ACCOUNT));
        list.addView(openScreenRow(text("Для разработчика", "Developer"),
                text("Инструменты для проверки и отладки", "Testing and debugging tools"),
                SCREEN_DEVELOPER));
        return root;
    }

    private View createSection(String screen) {
        LinearLayout[] holder = new LinearLayout[1];
        LinearLayout root = createScreen(holder);
        LinearLayout list = holder[0];
        switch (screen) {
            case SCREEN_NETWORK:
                list.addView(createTitle(text("Сеть", "Network"), false));
                addNetworkSection(list);
                break;
            case SCREEN_PLAYBACK:
                list.addView(createTitle(text("Офлайн и воспроизведение", "Offline and playback"), false));
                addPlaybackSection(list);
                break;
            case SCREEN_ADS:
                list.addView(createTitle(text("Реклама и подписка", "Ads and subscription"), false));
                addAdsSection(list);
                break;
            case SCREEN_RECOMMENDATIONS:
                list.addView(createTitle(text("Рекомендации", "Recommendations"), false));
                addRecommendationsSection(list);
                break;
            case SCREEN_MUSIC:
                list.addView(createTitle(text("Своя музыка", "Your music"), false));
                addMusicSection(list);
                break;
            case SCREEN_PRIVACY:
                list.addView(createTitle(text("Батарея и конфиденциальность", "Battery and privacy"), false));
                addPrivacySection(list);
                break;
            case SCREEN_UPDATES:
                list.addView(createTitle(text("Обновления и данные", "Updates and data"), false));
                addUpdatesSection(list);
                break;
            case SCREEN_ACCOUNT:
                list.addView(createTitle(text("Аккаунт", "Account"), false));
                addAccountSection(list);
                break;
            default:
                list.addView(createTitle(text("Для разработчика", "Developer"), false));
                addDeveloperSection(list);
        }
        return root;
    }

    private void addNetworkSection(LinearLayout list) {
        TextView network = createText("Body.Secondary", text("Проверяю, откуда приложение выходит в интернет…", "Checking the app network location…"));
        network.setPadding(dimen("spacing_m"), 0, dimen("spacing_m"), dimen("spacing_s"));
        list.addView(network);
        checkNetwork(network);
        list.addView(createToggleRow(
                text("Не выходить в сеть с российского IP", "Stay offline on a Russian IP"),
                text("Если приложение выходит в интернет с российского IP, оно не обращается ни к SoundCloud, "
                                + "ни к поиску Arsound. Играют скачанные и импортированные треки. Страна определяется "
                                + "через Cloudflare, проверяется заново при смене сети и раз в 30 секунд, пока идут запросы; "
                                + "если проверка не прошла, запросы ждут следующей.",
                        "On a Russian IP the app contacts neither SoundCloud nor the Arsound search; downloaded and "
                                + "imported tracks still play. The country is checked through Cloudflare, again whenever "
                                + "the network changes and every 30 seconds while requests go; after a failed check "
                                + "requests wait for the next one."),
                Settings.isRegionGuardEnabled(),
                (button, checked) -> Settings.putBoolean(Settings.REGION_GUARD, checked)
        ));
        list.addView(createActionRow(
                text("Проверить IP снова", "Check IP again"),
                text("Если включили VPN или сменили сеть, а SoundCloud всё ещё не работает.",
                        "If you turned on a VPN or changed the network and SoundCloud still does not work."),
                v -> app.revanced.extension.soundcloud.network.RegionGuard.recheck(() -> {
                    String country = app.revanced.extension.soundcloud.network.RegionGuard.lastCountry();
                    Toast.makeText(this, country == null
                                    ? text("Не удалось проверить страну", "Could not check the country")
                                    : text("Страна IP: ", "IP country: ") + country,
                            Toast.LENGTH_SHORT).show();
                })
        ));
        addDnsOptions(list);
        list.addView(createToggleRow(
                text("Показывать статус сети", "Show network status"),
                text("Плашка вверху главного экрана, когда нет интернета или SoundCloud отключён из-за российского IP. "
                                + "Нажмите на неё, чтобы проверить сеть снова.",
                        "A pill at the top of the home screen when there is no connection or SoundCloud is off "
                                + "because of a Russian IP. Tap it to check again."),
                Settings.isNetworkBannerEnabled(),
                (button, checked) -> Settings.putBoolean(Settings.NETWORK_BANNER, checked)
        ));
        list.addView(createToggleRow(
                text("Спрашивать перед проверкой устройства", "Ask before device check"),
                text("SoundCloud иногда проверяет, не бот ли вы, и открывает белое окно поверх приложения. "
                                + "Вместо этого появится вопрос: пройти проверку сейчас или позже.",
                        "SoundCloud sometimes checks that you are not a bot and opens a white screen over the app. "
                                + "Instead, you are asked whether to do it now or later."),
                Settings.isDataDomePromptEnabled(),
                (button, checked) -> Settings.putBoolean(Settings.DATADOME_PROMPT, checked)
        ));
    }

    private void addPlaybackSection(LinearLayout list) {
        TextView note = createText("Body.Secondary", text(
                "Скачанные треки всегда играют из файла на телефоне — сразу, без интернета и без трафика.",
                "Downloaded tracks always play from the file on the phone: right away, without a connection or data."));
        note.setPadding(dimen("spacing_m"), 0, dimen("spacing_m"), dimen("spacing_s"));
        list.addView(note);
        list.addView(createToggleRow(
                text("Сохранять плейлисты заранее", "Save playlists ahead"),
                text("В фоне сохраняет списки треков всех плейлистов и альбомов из библиотеки: названия, исполнителей, "
                                + "длительность. Без музыки и картинок, места почти не занимает. Нужно, чтобы плейлисты "
                                + "открывались без интернета.",
                        "Saves the track lists of every playlist and album in the library in the background: titles, "
                                + "artists, durations. No audio or images, takes almost no space. Lets playlists open offline."),
                Settings.isPlaylistPreloadEnabled(),
                (button, checked) -> Settings.putBoolean(Settings.PLAYLIST_PRELOAD, checked)
        ));
        list.addView(createToggleRow(
                text("Открывать плейлисты сразу", "Open playlists right away"),
                text("Плейлист показывается из памяти телефона, не дожидаясь SoundCloud, а обновляется в фоне.",
                        "A playlist is shown from the phone without waiting for SoundCloud and refreshes in the background."),
                Settings.isOfflineFirstEnabled(),
                (button, checked) -> Settings.setOfflineFirstEnabled(checked)
        ));
        list.addView(createToggleRow(
                text("Повторять при обрыве связи", "Retry on dropped connection"),
                text("Если трек из сети оборвался, плеер сам попробует ещё до трёх раз вместо ошибки "
                                + "«Track cannot be streamed».",
                        "If a streamed track stops, the player tries up to three more times instead of showing "
                                + "\"Track cannot be streamed\"."),
                Settings.isPlaybackRetryEnabled(),
                (button, checked) -> Settings.setPlaybackRetryEnabled(checked)
        ));
    }

    private void addAdsSection(LinearLayout list) {
        list.addView(createToggleRow(
                text("Блокировать рекламу", "Block ads"),
                text("Без аудио- и видеорекламы между треками и без рекламных баннеров в плеере, ленте, библиотеке, "
                                + "плейлистах и профилях. Применится после перезапуска SoundCloud.",
                        "No audio or video ads between tracks and no banner ads in the player, feed, library, "
                                + "playlists and profiles. Applies after restarting SoundCloud."),
                Settings.isBlockPlaybackAdsEnabled(),
                (button, checked) -> {
                    Settings.setBlockPlaybackAdsEnabled(checked);
                    Toast.makeText(this,
                            text("Перезапустите SoundCloud, чтобы применить", "Restart SoundCloud to apply"),
                            Toast.LENGTH_SHORT).show();
                }
        ));
        list.addView(createToggleRow(
                text("Скрывать предложения подписки", "Hide subscription offers"),
                text("Не показывает экран покупки SoundCloud Go и Go+, всплывающие предложения и баннер подписки "
                                + "на главной. Купить подписку в моде всё равно нельзя.",
                        "Hides the SoundCloud Go and Go+ purchase screen, popups and the subscription banner on the "
                                + "home screen. A subscription cannot be bought in the mod anyway."),
                Settings.isHideSubscriptionOffersEnabled(),
                (button, checked) -> Settings.setHideSubscriptionOffersEnabled(checked)
        ));
        list.addView(createToggleRow(
                text("Скрыть вкладку Upgrade", "Hide Upgrade tab"),
                text("Убирает вкладку Upgrade из нижней панели. Применится после перезапуска.",
                        "Removes the Upgrade tab from the bottom bar. Applies after a restart."),
                Settings.isHideUpgradeTabEnabled(),
                (button, checked) -> Settings.setHideUpgradeTabEnabled(checked)
        ));
        list.addView(createToggleRow(
                text("Скрыть баннер импорта плейлистов", "Hide the playlist import banner"),
                text("Убирает из библиотеки баннер «Transfer your gems». Кнопка «закрыть» у него временная: "
                                + "через несколько дней баннер возвращается сам. Применится после перезапуска.",
                        "Removes the \"Transfer your gems\" banner from the library. Its close button only hides it "
                                + "for a few days, after which it comes back. Applies after a restart."),
                Settings.isHideImportBannerEnabled(),
                (button, checked) -> Settings.setHideImportBannerEnabled(checked)
        ));
    }

    private void addRecommendationsSection(LinearLayout list) {
        LinearLayout duplicateOptions = new LinearLayout(this);
        duplicateOptions.setOrientation(LinearLayout.VERTICAL);
        list.addView(createToggleRow(
                text("Скрывать дубликаты", "Hide duplicates"),
                text("Один и тот же трек, перезалитый разными людьми, показывается на главной и в автовоспроизведении "
                                + "один раз. Одинаковыми считаются треки с тем же названием и длительностью (разница до 2 с). "
                                + "Лайки, плейлисты и профили не меняются.",
                        "The same song re-uploaded by different users appears once on the home screen and in autoplay. "
                                + "Tracks with the same title and duration (within 2 s) count as the same."),
                Settings.isDuplicateFilterEnabled(),
                (button, checked) -> {
                    Settings.putBoolean(Settings.DUPLICATE_FILTER, checked);
                    duplicateOptions.setVisibility(checked ? View.VISIBLE : View.GONE);
                }
        ));
        duplicateOptions.setVisibility(Settings.isDuplicateFilterEnabled() ? View.VISIBLE : View.GONE);
        duplicateOptions.addView(createToggleRow(
                text("Считать slowed, sped up и ремиксы тем же треком", "Treat slowed, sped up and remixes as the same song"),
                text("Если выключено, такие версии показываются отдельно.", "When off, these versions are shown separately."),
                Settings.isMergeEditedVersions(),
                (button, checked) -> Settings.putBoolean(Settings.MERGE_EDITED_VERSIONS, checked)
        ));
        list.addView(duplicateOptions);
    }

    private void addMusicSection(LinearLayout list) {
        list.addView(createActionRow(
                text("Импортированные файлы", "Imported files"),
                text("Добавить аудиофайлы с телефона и посмотреть уже добавленные.",
                        "Add audio files from the phone and see the ones already added."),
                v -> startActivity(new android.content.Intent(this, ReVancedSettingsActivity.class)
                        .putExtra(EXTRA_SCREEN, SCREEN_LOCAL_MUSIC))
        ));
        LinearLayout savedOptions = new LinearLayout(this);
        savedOptions.setOrientation(LinearLayout.VERTICAL);
        list.addView(createToggleRow(
                text("Плейлист «Импортированные»", "\"Imported\" playlist"),
                text("Приватный плейлист в библиотеке со всеми импортированными файлами. Треки в нём есть только "
                                + "на этом телефоне. Если удалить плейлист, при следующем запуске он появится снова. "
                                + "Применится после перезапуска.",
                        "A private playlist in the library with all imported files. Its tracks exist only on this phone. "
                                + "If you delete it, it comes back on the next start. Applies after a restart."),
                Settings.isSavedPlaylistEnabled(),
                (button, checked) -> {
                    Settings.putBoolean(Settings.SAVED_PLAYLIST, checked);
                    savedOptions.setVisibility(checked ? View.VISIBLE : View.GONE);
                }
        ));
        savedOptions.setVisibility(Settings.isSavedPlaylistEnabled() ? View.VISIBLE : View.GONE);
        savedOptions.addView(createToggleRow(
                text("Скрыть этот плейлист", "Hide this playlist"),
                text("Плейлист пропадёт из библиотеки, но импортированные треки останутся.",
                        "The playlist disappears from the library, imported tracks stay."),
                Settings.isSavedPlaylistHidden(),
                (button, checked) -> Settings.putBoolean(Settings.SAVED_PLAYLIST_HIDDEN, checked)
        ));
        list.addView(savedOptions);
        list.addView(createToggleRow(
                text("Свой порядок плейлистов и треков", "Custom playlist and track order"),
                text("Зажмите плейлист в «Библиотека → Плейлисты» или трек внутри плейлиста и перетащите. "
                                + "Чтобы закончить, коснитесь списка. Порядок хранится на телефоне, треки играют в нём же.",
                        "Press and hold a playlist in Library → Playlists, or a track inside a playlist, and drag it. "
                                + "Tap the list to finish. The order is kept on this phone and playback follows it."),
                Settings.isPlaylistOrderEnabled(),
                (button, checked) -> Settings.putBoolean(Settings.PLAYLIST_ORDER, checked)
        ));
        list.addView(createActionRow(
                text("Сбросить порядок", "Reset order"),
                text("Вернуть порядок SoundCloud для плейлистов и треков.",
                        "Go back to SoundCloud's order of playlists and tracks."),
                v -> {
                    app.revanced.extension.soundcloud.local.PlaylistOrder.reset();
                    app.revanced.extension.soundcloud.local.TrackOrder.resetAll();
                    Toast.makeText(this, text("Порядок сброшен", "Order reset"), Toast.LENGTH_SHORT).show();
                }
        ));
    }

    private void addPrivacySection(LinearLayout list) {
        list.addView(createToggleRow(
                text("Экономия батареи", "Battery saving"),
                text("Приложение реже выходит в сеть в фоне: новые сообщения проверяются раз в 5 минут вместо "
                                + "каждых 30 секунд, встроенные сервисы аналитики не отправляют отчёты. "
                                + "Применится после перезапуска.",
                        "The app goes online less in the background: new messages are checked every 5 minutes "
                                + "instead of every 30 seconds, built-in analytics services send no reports. Applies after a restart."),
                Settings.isPowerSavingEnabled(),
                (button, checked) -> Settings.setPowerSavingEnabled(checked)
        ));
        list.addView(createToggleRow(
                text("Телеметрия", "Telemetry"),
                text("Отправка статистики использования в SoundCloud. Применится после перезапуска.",
                        "Sends usage statistics to SoundCloud. Applies after a restart."),
                Settings.isTelemetryEnabled(),
                (button, checked) -> {
                    Settings.setTelemetryEnabled(checked);
                    Toast.makeText(this,
                            text("Перезапустите SoundCloud, чтобы применить", "Restart SoundCloud to apply"),
                            Toast.LENGTH_SHORT).show();
                }
        ));
    }

    private void addUpdatesSection(LinearLayout list) {
        list.addView(createToggleRow(
                text("Проверять при запуске", "Check on launch"),
                text("При запуске приложение смотрит на GitHub, вышла ли новая версия Arsound.",
                        "On launch the app checks GitHub for a new Arsound version."),
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
        list.addView(createActionRow(
                text("Сбросить данные SoundCloud", "Reset SoundCloud data"),
                text("Удаляет кэш, базу треков и настройки SoundCloud, как «Очистить данные» в Android. Помогает, "
                                + "если трек пишет «Недоступно в вашей стране», хотя в оригинале играет. Нужен интернет. "
                                + "Вход, настройки Arsound, скачанные треки и порядок сохранятся.",
                        "Removes the SoundCloud cache, track database and settings, like \"Clear data\" in Android. Helps when "
                                + "a track says it is not available in your country. Needs a connection. Login, Arsound settings, "
                                + "downloaded tracks and order are kept."),
                v -> confirmReset()
        ));
    }

    private void addAccountSection(LinearLayout list) {
        boolean signedIn = app.revanced.extension.soundcloud.search.YouTubeAccount.isSignedIn();
        list.addView(createActionRow(
                text("Аккаунт YouTube Music", "YouTube Music account"),
                signedIn
                        ? text("Вход выполнен. Нажмите, чтобы выйти.", "Signed in. Tap to sign out.")
                        : text("Не указан. Нажмите, чтобы войти.", "Not set. Tap to sign in."),
                v -> {
                    if (app.revanced.extension.soundcloud.search.YouTubeAccount.isSignedIn()) {
                        app.revanced.extension.soundcloud.search.YouTubeAccount.signOut();
                        Toast.makeText(this, text("Вы вышли из YouTube Music", "Signed out of YouTube Music"),
                                Toast.LENGTH_SHORT).show();
                        recreate();
                    } else {
                        app.revanced.extension.soundcloud.search.YouTubeLoginActivity.start(this);
                    }
                }
        ));
        list.addView(createActionRow(
                text("Зачем это? (?)", "What is it for? (?)"),
                text("Необязательно. Без входа всё работает, кроме треков с возрастным ограничением.",
                        "Optional. Everything works without it, except age-restricted tracks."),
                v -> new android.app.AlertDialog.Builder(this)
                        .setView(app.revanced.extension.soundcloud.permissions.WelcomePermissions.createDialogContent(this,
                                text("Поиск Arsound берёт треки из YouTube Music. Часть треков YouTube отдаёт только "
                                                + "после входа в аккаунт: у них возрастное ограничение 18+.",
                                        "The Arsound search takes tracks from YouTube Music. YouTube gives some tracks only "
                                                + "to signed-in listeners: they are age-restricted (18+)."),
                                text("Вход нужен только для них и указывается по желанию. Пароль вводится на странице "
                                                + "Google, Arsound его не видит. Сессия хранится только на этом телефоне "
                                                + "и отправляется только в YouTube.",
                                        "Signing in is only for them, and optional. The password is typed into Google's page; "
                                                + "Arsound does not see it. The session stays on this phone and goes only to YouTube."),
                                text("Аккаунт должен быть совершеннолетним по данным Google.",
                                        "Google must know the account as adult.")))
                        .setPositiveButton(text("Понятно", "Got it"), null)
                        .show()
        ));
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // The account screen shows the state after returning from the sign-in page.
        if (SCREEN_ACCOUNT.equals(getIntent().getStringExtra(EXTRA_SCREEN))) recreate();
    }

    private void addDeveloperSection(LinearLayout list) {
        LinearLayout developerOptions = new LinearLayout(this);
        developerOptions.setOrientation(LinearLayout.VERTICAL);
        list.addView(createToggleRow(
                text("Настройки для разработчика", "Developer options"),
                text("Инструменты для проверки и отладки. Обычно не нужны.",
                        "Testing and debugging tools. Usually not needed."),
                Settings.isDeveloperModeEnabled(),
                (button, checked) -> {
                    Settings.setDeveloperModeEnabled(checked);
                    developerOptions.setVisibility(checked ? View.VISIBLE : View.GONE);
                }
        ));
        developerOptions.setVisibility(Settings.isDeveloperModeEnabled() ? View.VISIBLE : View.GONE);
        list.addView(developerOptions);
        addDeveloperOptions(developerOptions);
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

        addLogOptions(container);
    }

    /**
     * The rare bugs of this mod show up once in a few days, so logcat is of no use: it needs a computer
     * and is wiped on reboot. With this on, the log is kept in a file that can be saved and read later.
     */
    private void addLogOptions(LinearLayout container) {
        TextView[] logDescription = new TextView[1];

        container.addView(createToggleRow(
                text("Журнал в файл", "Write the log to a file"),
                text("Пишет подробный журнал работы мода в файл внутри приложения. Нужен, чтобы поймать редкую "
                                + "поломку: включите и пользуйтесь как обычно, а когда баг повторится — сохраните журнал. "
                                + "Журнал занимает не больше 2 МБ: самые старые записи затираются новыми.",
                        "Writes a detailed log of the mod into a file inside the app. Use it to catch a rare bug: "
                                + "turn it on, keep using the app, and save the log once the bug shows up again. "
                                + "The log never grows past 2 MB: the oldest lines are dropped."),
                Settings.isFileLoggingEnabled(),
                (button, checked) -> {
                    Settings.setFileLoggingEnabled(checked);
                    // Without this the detailed lines are never written at all.
                    app.revanced.extension.shared.settings.BaseSettings.DEBUG.save(checked);
                    if (logDescription[0] != null) logDescription[0].setText(logSizeDescription());
                }
        ));

        View saveRow = createActionRow(
                text("Сохранить журнал", "Save the log"),
                logSizeDescription(),
                v -> saveLogToDownloads()
        );
        logDescription[0] = (TextView) ((ViewGroup) saveRow).getChildAt(1);
        container.addView(saveRow);

        container.addView(createActionRow(
                text("Очистить журнал", "Clear the log"),
                text("Удаляет накопленные записи. Делайте это перед тем, как ловить баг заново.",
                        "Deletes what was written so far. Do this before trying to catch a bug again."),
                v -> {
                    app.revanced.extension.shared.debug.LogFile.clear();
                    if (logDescription[0] != null) logDescription[0].setText(logSizeDescription());
                    Toast.makeText(this, text("Журнал очищен", "The log is cleared"), Toast.LENGTH_SHORT).show();
                }
        ));
    }

    private String logSizeDescription() {
        long size = app.revanced.extension.shared.debug.LogFile.size();
        if (size == 0) {
            return text("Журнал пуст.", "The log is empty.");
        }
        String kilobytes = (size / 1024) + " " + text("КБ", "KB");
        return text("Записано " + kilobytes + ". Нажмите, чтобы сохранить файл в Загрузки.",
                kilobytes + " written. Tap to save the file to Downloads.");
    }

    private void saveLogToDownloads() {
        Utils.runOnBackgroundThread(() -> {
            String log = app.revanced.extension.shared.debug.LogFile.read();
            if (log.isEmpty()) {
                Utils.runOnMainThread(() -> Toast.makeText(this,
                        text("Журнал пуст", "The log is empty"), Toast.LENGTH_SHORT).show());
                return;
            }

            String name = "arsound-log-"
                    + new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US)
                    .format(new java.util.Date()) + ".txt";
            try {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS);

                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new java.io.IOException("The file could not be created");

                try (java.io.OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new java.io.IOException("The file could not be opened");
                    output.write(log.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }

                Utils.runOnMainThread(() -> Toast.makeText(this,
                        text("Сохранено: Загрузки/" + name, "Saved to Downloads/" + name),
                        Toast.LENGTH_LONG).show());
            } catch (Exception ex) {
                Logger.printException(() -> "Could not save the log", ex);
                Utils.runOnMainThread(() -> Toast.makeText(this,
                        text("Не удалось сохранить журнал", "Could not save the log"),
                        Toast.LENGTH_LONG).show());
            }
        });
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
                .setMessage(text("SoundCloud перезапустится и заново загрузит библиотеку с сервера — нужен рабочий интернет. "
                                + "Без него плейлисты не откроются, пока сеть не появится. Вход, настройки Arsound, "
                                + "скачанные треки, порядок плейлистов и импортированная музыка останутся.",
                        "SoundCloud restarts and downloads your library from the server again, so a working connection "
                                + "is needed. Without it playlists stay empty until the network is back. Login, Arsound "
                                + "settings, downloaded tracks, playlist order and imported music are kept."))
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
