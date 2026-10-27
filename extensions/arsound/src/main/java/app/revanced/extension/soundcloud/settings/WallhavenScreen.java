package app.revanced.extension.soundcloud.settings;

import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.wallpaper.Wallhaven;
import app.revanced.extension.soundcloud.wallpaper.WallhavenGrid;
import app.revanced.extension.soundcloud.wallpaper.WallpaperStore;

/**
 * The Wallhaven browser: a search field, the sortings as chips, a filter dialog, and an endless grid of thumbnails.
 * A tap opens a preview with "Set as background", which downloads the full picture and makes it the app's wallpaper.
 * <p>
 * Nothing here touches the network while the screen is built (the settings search builds every screen off screen);
 * the first page is asked for when the screen is shown.
 */
final class WallhavenScreen {
    /** The page is asked for when this many cells are left below the visible ones. */
    private static final int PRELOAD_CELLS = 6;
    private static final int DEFAULT_WAIT_SECONDS = 20;

    private final ReVancedSettingsActivity activity;
    private final Wallhaven.Query query;
    private final WallhavenGrid grid;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int accent, primary, secondary, card;

    private GridView gridView;
    private LinearLayout state;
    private TextView stateText;
    private ProgressBar spinner;
    private LinearLayout stateAction;
    /** What the button of the message says and does now. */
    private String stateActionLabel;
    private Runnable stateTarget;
    private TextView banner;
    private View rangeRow;
    private EditText field;
    private final List<TextView> sortChips = new ArrayList<>();
    private final List<TextView> rangeChips = new ArrayList<>();

    /** Answers of an older search are dropped: the screen counts its searches. */
    private int generation;
    private int page;
    private int lastPage = 1;
    private boolean loading;
    /** Set after a failure of the next page: the grid does not ask again until the user taps to retry. */
    private boolean paused;
    /** The user chose to search without the key the service refused. */
    private boolean guest;
    /** The user picked a sorting by hand, so a search text does not switch it to relevance. */
    private boolean sortChosen;
    private Runnable countdown;

    WallhavenScreen(ReVancedSettingsActivity activity) {
        this.activity = activity;
        query = Wallhaven.Query.fromJson(Settings.getString(Settings.WALLHAVEN_LAST, ""));
        grid = new WallhavenGrid(activity);
        accent = ArsoundTheme.palette(activity, "special");
        primary = ArsoundTheme.palette(activity, "primary");
        secondary = ArsoundTheme.palette(activity, "secondary");
        card = ArsoundTheme.palette(activity, "highlight");
    }

    private static String text(String russian, String english) {
        return ReVancedSettingsActivity.text(russian, english);
    }

    private int dp(int value) {
        return activity.dp(value);
    }

    View create() {
        LinearLayout root = activity.createRoot();
        root.addView(createTopBar());
        root.addView(createChips(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout content = new FrameLayout(activity);
        gridView = createGrid();
        content.addView(gridView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(createState(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        banner = createBanner();
        FrameLayout.LayoutParams bannerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bannerParams.bottomMargin = dp(16);
        bannerParams.leftMargin = bannerParams.rightMargin = dp(16);
        content.addView(banner, bannerParams);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        applyShape();
        // The keyboard stays down until the search field is tapped.
        activity.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        // The first page is asked for once the screen is on its way to the display, not while it is built.
        root.post(this::reload);
        return root;
    }

    // region Views

    /** The back button of the settings screens, with the search field and the filter button after it. */
    private View createTopBar() {
        LinearLayout bar = (LinearLayout) activity.createToolbar();
        bar.addView(createSearchField(), new LinearLayout.LayoutParams(0, dp(48), 1f));
        ImageButton filters = new ImageButton(activity);
        filters.setImageDrawable(activity.lineIcon(new float[]{.18f, .24f, .82f, .24f, .56f, .54f, .56f, .8f, .44f, .72f,
                .44f, .54f, .18f, .24f}, primary));
        filters.setScaleType(ImageView.ScaleType.FIT_CENTER);
        filters.setPadding(dp(11), dp(11), dp(11), dp(11));
        filters.setBackgroundResource(activity.themeAttribute(android.R.attr.selectableItemBackgroundBorderless));
        filters.setContentDescription(text("Фильтры", "Filters"));
        filters.setOnClickListener(v -> showFilters());
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        filterParams.leftMargin = dp(4);
        bar.addView(filters, filterParams);
        return bar;
    }

    /** Styled like the field of the settings search: a pill in the card colour with a thin accent edge. */
    private View createSearchField() {
        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(card);
        shape.setCornerRadius(dp(24));
        shape.setStroke(dp(1), (accent & 0x00FFFFFF) | 0x66000000);
        pill.setBackground(shape);

        ImageView lens = new ImageView(activity);
        lens.setImageDrawable(new SearchGlyph(SearchGlyph.MAGNIFIER, secondary));
        lens.setPadding(dp(12), dp(12), dp(8), dp(12));
        pill.addView(lens, new LinearLayout.LayoutParams(dp(44), dp(48)));

        field = new EditText(activity);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setText(query.text);
        field.setHint(text("Поиск обоев на Wallhaven", "Search Wallhaven"));
        field.setHintTextColor(secondary);
        field.setTextColor(primary);
        field.setHighlightColor((accent & 0x00FFFFFF) | 0x55000000);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setPadding(0, 0, 0, 0);
        field.setOnEditorActionListener((view, action, event) -> {
            hideKeyboard();
            submitText();
            return true;
        });
        pill.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        ImageButton clear = new ImageButton(activity);
        clear.setImageDrawable(new SearchGlyph(SearchGlyph.CROSS, primary));
        clear.setScaleType(ImageView.ScaleType.FIT_CENTER);
        clear.setPadding(dp(10), dp(10), dp(10), dp(10));
        clear.setBackgroundResource(activity.themeAttribute(android.R.attr.selectableItemBackgroundBorderless));
        clear.setContentDescription(text("Очистить", "Clear"));
        clear.setOnClickListener(v -> {
            boolean had = !field.getText().toString().trim().isEmpty() || !query.text.isEmpty();
            field.setText("");
            if (had) submitText();
        });
        pill.addView(clear, new LinearLayout.LayoutParams(dp(44), dp(48)));
        return pill;
    }

    /** The sortings as chips; the periods of the top list in a row of their own below, only while the top list is chosen. */
    private View createChips() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);

        String[] sortLabels = {text("Топ", "Top"), text("Новые", "Latest"), text("Просмотры", "Most viewed"),
                text("Избранное", "Favorites"), text("Релевантные", "Relevance"), text("Случайные", "Random")};
        LinearLayout sorts = chipRow();
        for (int i = 0; i < Wallhaven.SORTINGS.length; i++) {
            String sorting = Wallhaven.SORTINGS[i];
            TextView chip = chip(sortLabels[i], sorting.equals(query.sorting), v -> {
                sortChosen = true;
                chooseSorting(sorting);
                reload();
            });
            sortChips.add(chip);
            sorts.addView(chip, chipParams());
        }
        column.addView(scrolling(sorts));

        String[] rangeLabels = {text("День", "Day"), text("3 дня", "3 days"), text("Неделя", "Week"), text("Месяц", "Month"),
                text("3 месяца", "3 months"), text("Полгода", "6 months"), text("Год", "Year")};
        LinearLayout ranges = chipRow();
        for (int i = 0; i < Wallhaven.RANGES.length; i++) {
            String range = Wallhaven.RANGES[i];
            TextView chip = chip(rangeLabels[i], range.equals(query.range), v -> {
                query.range = range;
                styleChips(rangeChips, Wallhaven.RANGES, range);
                reload();
            });
            rangeChips.add(chip);
            ranges.addView(chip, chipParams());
        }
        rangeRow = scrolling(ranges);
        column.addView(rangeRow);
        return column;
    }

    private LinearLayout chipRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(4), dp(12), dp(4));
        return row;
    }

    private View scrolling(View row) {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        return params;
    }

    private TextView chip(String label, boolean selected, View.OnClickListener listener) {
        TextView chip = activity.createText("H4.Primary", label);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
        chip.setOnClickListener(listener);
        styleChip(chip, selected);
        return chip;
    }

    private void styleChip(TextView chip, boolean selected) {
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(100));
        pill.setColor(selected ? accent : card);
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x33000000), pill, null));
        chip.setTextColor(selected ? ArsoundDialog.contrast(accent) : primary);
    }

    private void styleChips(List<TextView> chips, String[] values, String selected) {
        for (int i = 0; i < chips.size(); i++) styleChip(chips.get(i), values[i].equals(selected));
    }

    private void chooseSorting(String sorting) {
        query.sorting = sorting;
        styleChips(sortChips, Wallhaven.SORTINGS, sorting);
        rangeRow.setVisibility(Wallhaven.TOPLIST.equals(sorting) ? View.VISIBLE : View.GONE);
    }

    private GridView createGrid() {
        GridView view = new GridView(activity);
        view.setNumColumns(2);
        view.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        view.setHorizontalSpacing(dp(10));
        view.setVerticalSpacing(dp(10));
        view.setPadding(dp(12), dp(6), dp(12), dp(12));
        view.setClipToPadding(false);
        view.setSelector(new ColorDrawable(Color.TRANSPARENT));
        view.setCacheColorHint(Color.TRANSPARENT);
        view.setVerticalScrollBarEnabled(false);
        view.setAdapter(grid);
        view.setOnItemClickListener((parent, cell, position, id) -> {
            Wallhaven.Item item = grid.item(position);
            if (item != null) preview(item);
        });
        view.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView list, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }

            @Override
            public void onScroll(AbsListView list, int first, int visible, int total) {
                if (loading || paused || grid.size() == 0 || page >= lastPage) return;
                if (first + visible >= total - PRELOAD_CELLS) loadPage(page + 1);
            }
        });
        return view;
    }

    /** The message in the middle of the screen, with a button when there is something to do about it. */
    private View createState() {
        state = new LinearLayout(activity);
        state.setOrientation(LinearLayout.VERTICAL);
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(32), dp(32), dp(32), dp(32));
        state.setVisibility(View.GONE);
        // The grid below shows its empty cells while the first page loads; the state takes the touches only when it shows a message.
        state.setClickable(false);

        spinner = new ProgressBar(activity);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(accent));
        state.addView(spinner, new LinearLayout.LayoutParams(dp(36), dp(36)));

        stateText = activity.createText("Body.Secondary", "");
        stateText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = dp(12);
        state.addView(stateText, textParams);

        stateAction = new LinearLayout(activity);
        stateAction.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(16);
        state.addView(stateAction, actionParams);
        return state;
    }

    /** A pill at the bottom for trouble with a later page, so the pictures already there stay in view. */
    private TextView createBanner() {
        TextView view = activity.createText("H4.Primary", "");
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ArsoundTheme.palette(activity, "dialog"));
        shape.setCornerRadius(dp(100));
        shape.setStroke(dp(1), (accent & 0x00FFFFFF) | 0x66000000);
        view.setBackground(shape);
        view.setElevation(dp(6));
        view.setVisibility(View.GONE);
        return view;
    }

    /** The cells follow the shape of the pictures asked for: tall ones for a phone, wide ones otherwise. */
    private void applyShape() {
        grid.setRatio(query.portrait ? 4f / 3f : .75f);
        rangeRow.setVisibility(Wallhaven.TOPLIST.equals(query.sorting) ? View.VISIBLE : View.GONE);
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (keyboard != null && field != null) keyboard.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    // endregion

    // region Searching

    private void submitText() {
        String typed = field.getText().toString().trim();
        if (typed.equals(query.text)) return;
        query.text = typed;
        // A text is looked for by relevance unless a sorting was picked by hand.
        if (!typed.isEmpty() && !sortChosen && Wallhaven.TOPLIST.equals(query.sorting)) chooseSorting(Wallhaven.RELEVANCE);
        if (typed.isEmpty() && !sortChosen && Wallhaven.RELEVANCE.equals(query.sorting)) chooseSorting(Wallhaven.TOPLIST);
        reload();
    }

    /** Starts the search over from the first page. */
    private void reload() {
        if (isGone()) return;
        generation++;
        cancelCountdown();
        paused = false;
        page = 0;
        lastPage = 1;
        loading = false;
        query.seed = "";
        Settings.putString(Settings.WALLHAVEN_LAST, query.toJson());
        grid.clear();
        applyShape();
        hideProblems();
        showLoading();
        grid.setLoadingMore(true);
        loadPage(1);
    }

    private void loadPage(int number) {
        if (loading || isGone()) return;
        loading = true;
        int mine = generation;
        Wallhaven.Query asked = query.copy();
        String key = guest ? "" : Wallhaven.key();
        if (number > 1) grid.setLoadingMore(true);
        Utils.runOnBackgroundThread(() -> {
            Wallhaven.Page result = null;
            IOException failure = null;
            try {
                result = Wallhaven.search(asked, number, key);
            } catch (IOException ex) {
                failure = ex;
            } catch (Exception ex) {
                // An answer the screen cannot read counts as a failed call.
                failure = new IOException(ex);
            }
            Wallhaven.Page page = result;
            IOException error = failure;
            Utils.runOnMainThread(() -> {
                if (mine != generation || isGone()) return;
                loading = false;
                grid.setLoadingMore(false);
                if (error != null) showFailure(error, number);
                else showPage(page);
            });
        });
    }

    private void showPage(Wallhaven.Page result) {
        page = result.page;
        lastPage = result.lastPage;
        if (Wallhaven.RANDOM.equals(query.sorting) && !result.seed.isEmpty()) query.seed = result.seed;
        grid.add(result.items);
        hideProblems();
        if (grid.size() == 0) {
            showMessage(text("Ничего не найдено. Измените запрос или фильтры.", "Nothing found. Change the search or the filters."),
                    null, null);
            return;
        }
        hideState();
        // A page of pictures that were all seen before leaves the grid short: ask for the next one.
        gridView.post(() -> {
            if (!loading && !paused && page < lastPage && gridView.getLastVisiblePosition() >= grid.getCount() - PRELOAD_CELLS) {
                loadPage(page + 1);
            }
        });
    }

    private void showFailure(IOException error, int number) {
        Runnable again = () -> {
            hideProblems();
            paused = false;
            if (number == 1) {
                showLoading();
                grid.setLoadingMore(true);
            }
            loadPage(number);
        };
        String message;
        String action = text("Повторить", "Try again");
        if (error instanceof Wallhaven.ApiException) {
            int code = ((Wallhaven.ApiException) error).code;
            if (code == 401) {
                // A key the service does not know is never sent again behind the user's back: the user decides.
                message = text("Wallhaven не принял API-ключ (ошибка 401). Исправьте ключ в «Оформление → Обои» или ищите без него.",
                        "Wallhaven did not accept the API key (error 401). Fix the key in Appearance > Wallpaper, or search without it.");
                problem(number, message, text("Искать без ключа", "Search without the key"), () -> {
                    guest = true;
                    reload();
                });
                return;
            }
            if (code == 429) {
                int wait = ((Wallhaven.ApiException) error).retryAfter;
                waitAndRetry(number, wait > 0 ? Math.min(wait, 90) : DEFAULT_WAIT_SECONDS, again);
                return;
            }
            message = text("Wallhaven ответил ошибкой " + code + ". Попробуйте позже.", "Wallhaven answered with error " + code + ". Try later.");
        } else if (error instanceof RegionGuard.BlockedException) {
            message = text("Сеть закрыта защитой по стране IP, поэтому Wallhaven не открыть. Настройки — раздел «Сеть».",
                    "The network is closed by the IP country guard, so Wallhaven cannot be reached. See Settings > Network.");
        } else {
            message = text("Не удалось связаться с wallhaven.cc. Проверьте интернет.", "Could not reach wallhaven.cc. Check the connection.");
        }
        problem(number, message, action, again);
    }

    /** The service asked to slow down: counts the wait down on screen and asks again by itself. */
    private void waitAndRetry(int number, int seconds, Runnable again) {
        int mine = generation;
        int[] left = {seconds};
        countdown = new Runnable() {
            @Override
            public void run() {
                if (mine != generation || isGone()) return;
                if (left[0] <= 0) {
                    countdown = null;
                    again.run();
                    return;
                }
                problem(number, text("Wallhaven просит не частить. Повтор через " + left[0] + " с.",
                        "Wallhaven asks to slow down. Trying again in " + left[0] + " s."),
                        text("Повторить сейчас", "Try now"), () -> {
                            cancelCountdown();
                            again.run();
                        });
                left[0]--;
                handler.postDelayed(this, 1000);
            }
        };
        countdown.run();
    }

    private void cancelCountdown() {
        if (countdown != null) handler.removeCallbacks(countdown);
        countdown = null;
    }

    /** Trouble with the first page fills the screen; trouble with a later one is a pill over the pictures. */
    private void problem(int number, String message, String action, Runnable onAction) {
        paused = true;
        if (number == 1 && grid.size() == 0) {
            showMessage(message, action, onAction);
            return;
        }
        banner.setText(message + "  ·  " + action);
        banner.setOnClickListener(v -> onAction.run());
        banner.setVisibility(View.VISIBLE);
    }

    // endregion

    // region State

    private void showLoading() {
        stateText.setVisibility(View.GONE);
        stateAction.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        state.setClickable(false);
        state.setVisibility(View.VISIBLE);
    }

    private void showMessage(String message, String actionLabel, Runnable action) {
        spinner.setVisibility(View.GONE);
        stateText.setText(message);
        stateText.setVisibility(View.VISIBLE);
        // The countdown writes its message every second; the button stays, only what it does is renewed.
        stateTarget = action;
        if (actionLabel == null) {
            stateAction.removeAllViews();
            stateActionLabel = null;
            stateAction.setVisibility(View.GONE);
        } else {
            if (!actionLabel.equals(stateActionLabel)) {
                stateAction.removeAllViews();
                stateAction.addView(activity.createPillButton(actionLabel, true, v -> {
                    if (stateTarget != null) stateTarget.run();
                }));
                stateActionLabel = actionLabel;
            }
            stateAction.setVisibility(View.VISIBLE);
        }
        state.setClickable(true);
        state.setVisibility(View.VISIBLE);
    }

    private void hideState() {
        state.setVisibility(View.GONE);
    }

    private void hideProblems() {
        banner.setVisibility(View.GONE);
    }

    private boolean isGone() {
        return activity.isFinishing() || activity.isDestroyed();
    }

    // endregion

    // region Filters

    private void showFilters() {
        int[] categories = {query.categories};
        boolean[] portrait = {query.portrait};
        int[] adult = {query.adultPurity};

        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setTag(ArsoundDialog.KEEP_COLORS);
        form.addView(heading(text("Категории", "Categories")));
        String[] names = {text("Общие", "General"), text("Аниме", "Anime"), text("Люди", "People")};
        int[] bits = {Wallhaven.GENERAL, Wallhaven.ANIME, Wallhaven.PEOPLE};
        for (int i = 0; i < bits.length; i++) {
            int bit = bits[i];
            form.addView(activity.createCheckRow(names[i], (categories[0] & bit) != 0, (button, checked) -> {
                int next = checked ? categories[0] | bit : categories[0] & ~bit;
                // At least one category stays on: the service answers nothing otherwise.
                if (next == 0) {
                    button.setChecked(true);
                    return;
                }
                categories[0] = next;
            }));
        }
        form.addView(heading(text("Размер", "Size")));
        form.addView(activity.createCheckRow(text("Только вертикальные, от 1080×1920", "Tall pictures only, at least 1080×1920"),
                portrait[0], (button, checked) -> portrait[0] = checked));
        if (Wallhaven.adultAllowed()) {
            form.addView(heading("18+"));
            form.addView(activity.createCheckRow("Sketchy", (adult[0] & Wallhaven.SKETCHY) != 0,
                    (button, checked) -> adult[0] = checked ? adult[0] | Wallhaven.SKETCHY : adult[0] & ~Wallhaven.SKETCHY));
            form.addView(activity.createCheckRow("NSFW", (adult[0] & Wallhaven.NSFW) != 0,
                    (button, checked) -> adult[0] = checked ? adult[0] | Wallhaven.NSFW : adult[0] & ~Wallhaven.NSFW));
        }
        new ArsoundDialog(activity)
                .setTitle(text("Фильтры", "Filters"))
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(text("Применить", "Apply"), (dialog, which) -> {
                    boolean changed = query.categories != categories[0] || query.portrait != portrait[0] || query.adultPurity != adult[0];
                    query.categories = categories[0];
                    query.portrait = portrait[0];
                    query.adultPurity = adult[0];
                    if (changed) reload();
                })
                .show();
    }

    private TextView heading(String label) {
        TextView heading = activity.createText("H4.Secondary", label);
        heading.setPadding(activity.dimen("spacing_m"), dp(12), activity.dimen("spacing_m"), dp(2));
        heading.setTextColor(secondary);
        return heading;
    }

    // endregion

    // region Preview and download

    /** A larger look at a picture with its facts, the link to its page and the button that makes it the wallpaper. */
    private void preview(Wallhaven.Item item) {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), 0, dp(24), 0);
        body.setTag(ArsoundDialog.KEEP_COLORS);

        ImageView picture = new ImageView(activity);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable base = new GradientDrawable();
        base.setColor(card);
        picture.setBackground(base);
        float radius = dp(16);
        picture.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        picture.setClipToOutline(true);
        Bitmap thumbnail = grid.thumbnail(item);
        if (thumbnail != null) picture.setImageBitmap(thumbnail);
        int height = Math.min(dp(380), Math.round(activity.getResources().getDisplayMetrics().heightPixels * .36f));
        body.addView(picture, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));

        TextView facts = activity.createText("Body.Secondary", item.resolution + "  ·  "
                + String.format(Locale.US, "%.1f", item.fileSize / 1048576f) + " MB  ·  "
                + text(item.views + " просмотров, " + item.favorites + " в избранном",
                item.views + " views, " + item.favorites + " favorites"));
        facts.setTextColor(secondary);
        facts.setPadding(0, dp(12), 0, 0);
        body.addView(facts);

        TextView source = activity.createText("Body.Secondary", text("Источник и автор: ", "Source and author: ")
                + Wallhaven.pageUrl(item.id).replace("https://", ""));
        source.setTextColor(accent);
        source.setPadding(0, dp(4), 0, dp(4));
        source.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Wallhaven.pageUrl(item.id))));
            } catch (Exception ex) {
                Logger.printInfo(() -> "Wallhaven: no app to open the link");
            }
        });
        body.addView(source);

        new ArsoundDialog(activity)
                .setView(body)
                .setNegativeButton(text("Закрыть", "Close"), null)
                .setPositiveButton(text("Сделать фоном", "Set as background"), (dialog, which) -> download(item))
                .show();
    }

    /** Downloads the full picture with a progress bar, keeps it as the wallpaper and leaves the browser. */
    private void download(Wallhaven.Item item) {
        AtomicBoolean cancelled = new AtomicBoolean();
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.setTag(ArsoundDialog.KEEP_COLORS);
        TextView label = activity.createText("Body.Secondary", item.resolution);
        label.setTextColor(secondary);
        box.addView(label);
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(true);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setIndeterminateTintList(ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        barParams.topMargin = dp(12);
        box.addView(bar, barParams);

        Dialog dialog = new ArsoundDialog(activity)
                .setTitle(text("Скачиваю обои…", "Downloading the wallpaper…"))
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, which) -> cancelled.set(true))
                .show();

        File target = new File(activity.getCacheDir(), "wallpaper-download.tmp");
        long[] lastUpdate = {0};
        Utils.runOnBackgroundThread(() -> {
            boolean saved = false;
            try {
                Wallhaven.download(item.fullUrl, target, (done, total) -> {
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate[0] < 100) return;
                    lastUpdate[0] = now;
                    Utils.runOnMainThread(() -> {
                        if (total > 0) {
                            bar.setIndeterminate(false);
                            bar.setProgress((int) (done * 1000 / total));
                        }
                    });
                }, cancelled);
                Utils.runOnMainThread(() -> {
                    label.setText(text("Готовлю обои…", "Preparing the wallpaper…"));
                    bar.setIndeterminate(true);
                });
                saved = WallpaperStore.install(activity.getApplicationContext(), target, item.id);
            } catch (IOException ex) {
                if (!cancelled.get()) Logger.printInfo(() -> "Wallhaven: the download failed: " + ex.getClass().getSimpleName());
            } finally {
                target.delete();
            }
            boolean ok = saved;
            Utils.runOnMainThread(() -> {
                if (dialog.isShowing()) dialog.dismiss();
                if (cancelled.get() || isGone()) return;
                Toast.makeText(activity, ok ? text("Обои установлены", "Wallpaper set")
                        : text("Не удалось скачать обои", "Could not download the wallpaper"), Toast.LENGTH_SHORT).show();
                if (ok) activity.finish();
            });
        });
    }

    // endregion
}
