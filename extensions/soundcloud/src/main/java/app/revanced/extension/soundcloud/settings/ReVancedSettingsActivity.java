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

    private static final boolean RUSSIAN = "ru".equals(Locale.getDefault().getLanguage());

    private static String text(String russian, String english) {
        return RUSSIAN ? russian : english;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            setContentView(createContent());
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

        TextView title = createText("H1.Primary", "Arsound");
        title.setPadding(dimen("spacing_m"), dimen("spacing_s"), dimen("spacing_m"), dimen("spacing_l"));
        list.addView(title);

        list.addView(createSubHeading(text("Сеть", "Network")));
        TextView network = createText("Body.Secondary", text("Проверяю, откуда приложение выходит в интернет…", "Checking the app network location…"));
        network.setPadding(dimen("spacing_m"), 0, dimen("spacing_m"), dimen("spacing_s"));
        list.addView(network);
        checkNetwork(network);

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

        list.addView(createSubHeading(text("Данные", "Data")));
        list.addView(createActionRow(
                text("Сбросить данные SoundCloud", "Reset SoundCloud data"),
                text("Удалит кэш, базу треков и настройки SoundCloud, как «Очистить данные» в Android. "
                                + "Вход в аккаунт, настройки Arsound и список скачанных треков сохранятся.",
                        "Removes the SoundCloud cache, track database and app settings, like \"Clear data\" in Android. "
                                + "Your login, Arsound settings and downloaded tracks list are kept."),
                v -> confirmReset()
        ));

        return root;
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
