package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.wallpaper.Wallhaven;
import app.revanced.extension.soundcloud.wallpaper.WallhavenGrid;
import app.revanced.extension.soundcloud.wallpaper.WallhavenRules;

/**
 * The Wallhaven browser: a search field, a row of chips for the sorting, a filter sheet, and an endless grid of pictures.
 * A tap opens the full-screen preview ({@link WallpaperPreviewScreen}) with "Set as background".
 * <p>
 * The grid has three columns on a phone and cells in the shape of the pictures. The row of chips slides away when the
 * list is scrolled down and returns when it is scrolled up. Once scrolling has been still for a moment the cells in view
 * are made sharp ({@link WallhavenGrid#sharpenVisible}).
 * <p>
 * Nothing here touches the network while the screen is built (the settings search builds every screen off screen);
 * the first page is asked for when the screen is shown.
 */
final class WallhavenScreen extends SettingsScreen {
    static final String ID = "wallhaven";

    /** The next page is asked for when this many rows are left below the visible ones. */
    private static final int PRELOAD_ROWS = 3;
    private static final int DEFAULT_WAIT_SECONDS = 20;
    /** Scrolling must be still this long before the cells in view are made sharp. */
    private static final long SETTLE_MS = 400;
    /** The row of chips: a chip and the space around it. */
    private static final int HEADER_DP = WallpaperChip.HEIGHT_DP + 10;
    private static final int GRID_GAP_DP = 8, GRID_SIDE_DP = 12;

    private final Wallhaven.Query query;
    private final WallhavenGrid grid;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int accent, primary, secondary, card;

    private GridView gridView;
    private View header;
    private TextView filterBadge;
    private LinearLayout state;
    private TextView stateText;
    private View spinner;
    private LinearLayout stateAction;
    /** What the button of the message says and does now. */
    private String stateActionLabel;
    private Runnable stateTarget;
    private TextView banner;
    private EditText field;
    private final List<TextView> sortChips = new ArrayList<>();
    private int columns = 3;
    private boolean headerShown = true;
    private int lastScrollPosition;
    /** The preview that is open, so closing the screen can give up its download. */
    private WallpaperPreviewScreen preview;

    /** Answers of an older search are dropped: the screen counts its searches. */
    private int generation;
    private int page;
    private int lastPage = 1;
    private boolean loading;
    /** Set after a failure of the next page: the grid does not ask again until the user taps to retry. */
    private boolean paused;
    /** The user chose to search without the key the service refused. */
    private boolean guest;
    /** The key that was refused when {@link #guest} was chosen; a different key ends the guest search. */
    private String guestKey = "";
    /** The user picked a sorting by hand, so a search text does not switch it to relevance. */
    private boolean sortChosen;
    private Runnable countdown;
    private final Runnable settle = this::sharpenVisible;

    WallhavenScreen(SettingsUi ui) {
        super(ui);
        query = Wallhaven.Query.fromJson(Settings.getString(Settings.WALLHAVEN_LAST, ""));
        grid = new WallhavenGrid(activity);
        accent = ArsoundTheme.palette(activity, "special");
        primary = ArsoundTheme.palette(activity, "primary");
        secondary = ArsoundTheme.palette(activity, "secondary");
        card = ArsoundTheme.palette(activity, "highlight");
    }

    private int dp(int value) {
        return ui.dp(value);
    }

    @Override
    View create() {
        app.revanced.extension.soundcloud.wallpaper.WallpaperDownload.sweepLeftovers(activity);
        LinearLayout root = ui.createRoot();
        root.addView(createTopBar());

        FrameLayout content = new FrameLayout(activity);
        gridView = createGrid();
        content.addView(gridView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(createState(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // After the message, so that the chips can still be tapped while the message is shown.
        header = createHeader();
        content.addView(header, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(HEADER_DP), Gravity.TOP));
        banner = createBanner();
        FrameLayout.LayoutParams bannerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bannerParams.bottomMargin = dp(16);
        bannerParams.leftMargin = bannerParams.rightMargin = dp(16);
        content.addView(banner, bannerParams);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        applyShape();
        updateFilterBadge();
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
        LinearLayout bar = (LinearLayout) ui.createToolbar();
        bar.addView(createSearchField(), new LinearLayout.LayoutParams(0, dp(48), 1f));

        FrameLayout filters = new FrameLayout(activity);
        ImageButton button = new ImageButton(activity);
        button.setImageDrawable(ui.lineIcon(new float[]{.18f, .24f, .82f, .24f, .56f, .54f, .56f, .8f, .44f, .72f,
                .44f, .54f, .18f, .24f}, primary));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setBackgroundResource(ui.themeAttribute(android.R.attr.selectableItemBackgroundBorderless));
        button.setContentDescription(text("Фильтры", "Filters"));
        button.setOnClickListener(v -> showFilters());
        filters.addView(button, new FrameLayout.LayoutParams(dp(48), dp(48)));

        // The number of filters that differ from the usual, on the corner of the button.
        filterBadge = ui.createText("Body.Secondary", "");
        filterBadge.setGravity(Gravity.CENTER);
        filterBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        filterBadge.setTextColor(app.revanced.extension.soundcloud.shared.Colors.contrast(accent));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(accent);
        filterBadge.setBackground(dot);
        filterBadge.setVisibility(View.GONE);
        filters.addView(filterBadge, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.END));
        ((FrameLayout.LayoutParams) filterBadge.getLayoutParams()).setMargins(0, dp(6), dp(6), 0);

        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        filterParams.leftMargin = dp(4);
        bar.addView(filters, filterParams);
        return bar;
    }

    private void updateFilterBadge() {
        int count = WallhavenRules.activeFilters(query.categories, query.portrait, Wallhaven.adultAllowed());
        filterBadge.setText(String.valueOf(count));
        filterBadge.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
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
        clear.setBackgroundResource(ui.themeAttribute(android.R.attr.selectableItemBackgroundBorderless));
        clear.setContentDescription(text("Очистить", "Clear"));
        clear.setOnClickListener(v -> {
            boolean had = !field.getText().toString().trim().isEmpty() || !query.text.isEmpty();
            field.setText("");
            if (had) submitText();
        });
        pill.addView(clear, new LinearLayout.LayoutParams(dp(44), dp(48)));
        return pill;
    }

    /**
     * One row of sorting chips over the grid, on a fade of the screen colour so the pictures under it do not fight the
     * words. The top list's chip says its period and opens the choice of it when tapped again.
     */
    private View createHeader() {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        int surface = ArsoundTheme.palette(activity, "surface") | 0xFF000000;
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(surface & 0x00FFFFFF) | 0xF2000000, (surface & 0x00FFFFFF) | 0xCC000000, surface & 0x00FFFFFF}));

        String[] sortLabels = {text("Топ", "Top"), text("Новые", "Latest"), text("Просмотры", "Most viewed"),
                text("Избранное", "Favorites"), text("Релевантные", "Relevance"), text("Случайные", "Random")};
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(GRID_SIDE_DP), dp(4), dp(GRID_SIDE_DP), dp(6));
        for (int i = 0; i < Wallhaven.SORTINGS.length; i++) {
            String sorting = Wallhaven.SORTINGS[i];
            TextView chip = WallpaperChip.create(ui, sortLabels[i], sorting.equals(query.sorting), v -> {
                if (Wallhaven.TOPLIST.equals(sorting) && Wallhaven.TOPLIST.equals(query.sorting)) {
                    showRanges();
                    return;
                }
                sortChosen = true;
                chooseSorting(sorting);
                reload();
            });
            sortChips.add(chip);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(8);
            row.addView(chip, params);
        }
        scroll.addView(row);
        updateTopLabel();
        return scroll;
    }

    /** The top list's chip carries the period and an arrow while the top list is chosen. */
    private void updateTopLabel() {
        TextView chip = sortChips.get(0);
        boolean top = Wallhaven.TOPLIST.equals(query.sorting);
        chip.setText(top ? text("Топ · ", "Top · ") + rangeLabel(query.range) + " ▾" : text("Топ", "Top"));
    }

    private static String rangeLabel(String range) {
        switch (range) {
            case "1d":
                return text("день", "day");
            case "3d":
                return text("3 дня", "3 days");
            case "1w":
                return text("неделя", "week");
            case "3M":
                return text("3 месяца", "3 months");
            case "6M":
                return text("полгода", "6 months");
            case "1y":
                return text("год", "year");
            default:
                return text("месяц", "month");
        }
    }

    /** The periods of the top list as chips in a small sheet. */
    private void showRanges() {
        String[] labels = {text("День", "Day"), text("3 дня", "3 days"), text("Неделя", "Week"), text("Месяц", "Month"),
                text("3 месяца", "3 months"), text("Полгода", "6 months"), text("Год", "Year")};
        LinearLayout content = ui.createColumn();
        content.setPadding(dp(20), 0, dp(20), dp(12));
        ThemeSheet[] sheet = new ThemeSheet[1];
        LinearLayout row = null;
        for (int i = 0; i < Wallhaven.RANGES.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowParams.topMargin = i == 0 ? 0 : dp(8);
                content.addView(row, rowParams);
            }
            String range = Wallhaven.RANGES[i];
            TextView chip = WallpaperChip.create(ui, labels[i], range.equals(query.range), v -> {
                sheet[0].dismiss();
                if (range.equals(query.range)) return;
                query.range = range;
                updateTopLabel();
                reload();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.leftMargin = i % 4 == 0 ? 0 : dp(8);
            row.addView(chip, params);
        }
        sheet[0] = new ThemeSheet(activity).setTitle(text("Период топа", "Period of the top list"))
                .setContent(content).setDim(.35f);
        sheet[0].show();
    }

    private void styleChips(List<TextView> chips, String[] values, String selected) {
        for (int i = 0; i < chips.size(); i++) WallpaperChip.style(ui, chips.get(i), values[i].equals(selected));
    }

    private void chooseSorting(String sorting) {
        query.sorting = sorting;
        styleChips(sortChips, Wallhaven.SORTINGS, sorting);
        updateTopLabel();
    }

    private GridView createGrid() {
        GridView view = new GridView(activity);
        view.setNumColumns(columns);
        view.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        view.setHorizontalSpacing(dp(GRID_GAP_DP));
        view.setVerticalSpacing(dp(GRID_GAP_DP));
        // The top padding keeps the first row below the chips.
        view.setPadding(dp(GRID_SIDE_DP), dp(HEADER_DP) + dp(2), dp(GRID_SIDE_DP), dp(12));
        view.setClipToPadding(false);
        view.setSelector(new ColorDrawable(Color.TRANSPARENT));
        view.setCacheColorHint(Color.TRANSPARENT);
        view.setVerticalScrollBarEnabled(false);
        view.setAdapter(grid);
        view.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            // Posted: the grid changes its own columns, which must not happen inside its layout pass.
            if (right - left != oldRight - oldLeft) v.post(this::applyColumns);
        });
        view.setOnItemClickListener((parent, cell, position, id) -> {
            Wallhaven.Item item = grid.item(position);
            if (item != null) openPreview(item);
        });
        view.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView list, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
                if (scrollState == SCROLL_STATE_IDLE) scheduleSettle();
                else handler.removeCallbacks(settle);
            }

            @Override
            public void onScroll(AbsListView list, int first, int visible, int total) {
                followScroll(list, first);
                if (loading || paused || grid.size() == 0 || page >= lastPage) return;
                if (first + visible >= total - PRELOAD_ROWS * columns) loadPage(page + 1);
            }
        });
        return view;
    }

    /** Works out how many columns fit and how wide a cell is, and tells the grid, which makes its sharp pictures at that size. */
    private void applyColumns() {
        int width = gridView.getWidth();
        if (width <= 0) return;
        float density = activity.getResources().getDisplayMetrics().density;
        columns = WallhavenRules.columns(Math.round(width / density), query.portrait);
        if (gridView.getNumColumns() != columns) gridView.setNumColumns(columns);
        int available = width - gridView.getPaddingLeft() - gridView.getPaddingRight();
        int cell = (available - (columns - 1) * dp(GRID_GAP_DP)) / columns;
        grid.setLayout(columns, cell);
        scheduleSettle();
    }

    private void sharpenVisible() {
        if (!isGone() && gridView != null) grid.sharpenVisible(gridView);
    }

    private void scheduleSettle() {
        handler.removeCallbacks(settle);
        handler.postDelayed(settle, SETTLE_MS);
    }

    /** The chips slide away when the list moves down and come back when it moves up or reaches the top. */
    private void followScroll(AbsListView list, int first) {
        View top = list.getChildAt(0);
        if (top == null) return;
        int rowHeight = top.getHeight() + dp(GRID_GAP_DP);
        int position = (first / Math.max(1, columns)) * rowHeight - top.getTop();
        int delta = position - lastScrollPosition;
        lastScrollPosition = position;
        if (position <= dp(8) || delta < -dp(6)) showHeader(true);
        else if (delta > dp(6)) showHeader(false);
    }

    private void showHeader(boolean shown) {
        if (headerShown == shown) return;
        headerShown = shown;
        header.animate().translationY(shown ? 0 : -dp(HEADER_DP)).alpha(shown ? 1f : 0f).setDuration(180).start();
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

        spinner = new app.revanced.extension.soundcloud.shared.A9Loader(activity, ArsoundTheme.palette(activity, "primary"));
        state.addView(spinner, new LinearLayout.LayoutParams(dp(36), dp(36)));

        stateText = ui.createText("Body.Secondary", "");
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
        TextView view = ui.createText("H4.Primary", "");
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
        grid.setRatio(WallhavenRules.cellRatio(query.portrait));
        if (gridView != null) applyColumns();
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
        updateFilterBadge();
        lastScrollPosition = 0;
        showHeader(true);
        hideProblems();
        showLoading();
        grid.setLoadingMore(true);
        // Taps on the chips or searches in a row ask the service once, for the last choice, after a short pause: each
        // tap was a call of its own, and the service refuses after 45 calls a minute. A single change goes at once.
        long now = android.os.SystemClock.uptimeMillis();
        boolean inARow = now - lastReloadAt < RELOAD_GAP_MS;
        lastReloadAt = now;
        if (!inARow) {
            loadPage(1);
            return;
        }
        int mine = generation;
        Utils.runOnMainThreadDelayed(() -> {
            if (mine == generation) loadPage(1);
        }, RELOAD_PAUSE_MS);
    }

    /** Changes closer together than this are a series; the series asks once, this long after its last change. */
    private static final long RELOAD_GAP_MS = 1_000;
    private static final long RELOAD_PAUSE_MS = 300;
    private long lastReloadAt;

    private void loadPage(int number) {
        if (loading || isGone()) return;
        loading = true;
        int mine = generation;
        Wallhaven.Query asked = query.copy();
        // The key was changed in the filters since it was refused: it is tried again.
        if (guest && !Wallhaven.key().equals(guestKey)) guest = false;
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
        scheduleSettle();
        // A page of pictures that were all seen before leaves the grid short: ask for the next one.
        gridView.post(() -> {
            if (!loading && !paused && page < lastPage && gridView.getLastVisiblePosition() >= grid.getCount() - PRELOAD_ROWS * columns) {
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
                message = text("Wallhaven не принял API-ключ (ошибка 401). Исправьте ключ в фильтрах каталога или ищите без него.",
                        "Wallhaven did not accept the API key (error 401). Fix the key in the catalogue Filters, or search without it.");
                problem(number, message, text("Искать без ключа", "Search without the key"), () -> {
                    guest = true;
                    guestKey = Wallhaven.key();
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
                stateAction.addView(ui.createPillButton(actionLabel, true, v -> {
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

    // region Filters and preview

    private void showFilters() {
        WallhavenFilterSheet.show(ui, query, this::reload, this::updateFilterBadge);
    }

    /** Opens the full-screen look at a picture; the grid stays as it is under it. */
    private void openPreview(Wallhaven.Item item) {
        if (preview != null) preview.release();
        preview = WallpaperPreviewScreen.show(ui, item, grid.thumbnail(item), activity::finish);
    }

    /** Gives up what is still downloading and stops loading thumbnails: the screen is closed. */
    @Override
    void onDestroy() {
        cancelCountdown();
        handler.removeCallbacks(settle);
        if (preview != null) preview.release();
        grid.close();
    }

    // endregion
}
