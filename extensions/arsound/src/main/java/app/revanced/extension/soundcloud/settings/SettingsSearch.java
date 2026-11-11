package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.search.SearchIndex;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The search of the main screen: the button in the title row, the field that grows out of it, and the results that
 * take the place of the section rows. It reads every settings screen once into a {@link SearchIndex}.
 */
final class SettingsSearch {
    /** The screens are read for the search only after the main screen has come in: its animation is not held up. */
    private static final long INDEX_DELAY_MS = 600;
    /** A query is looked up once the typing has paused this long, not on every letter. */
    private static final long TYPING_PAUSE_MS = 150;

    private final ReVancedSettingsActivity activity;
    private final SettingsUi ui;
    /** Adds the rows of the main screen's sections; they are in the index as sections of their own. */
    private final Consumer<LinearLayout> mainRows;
    private final List<SectionScreen> sections;

    private LinearLayout searchSections;
    private LinearLayout searchResults;
    private View searchTitleRow;
    private View searchButton;
    private View searchBar;
    private EditText searchField;
    private boolean searchOpen;
    private Animator searchReveal;
    /** The finished index; null until every screen has been read. */
    private SearchIndex searchIndex;
    /** The index being read, a screen at a time; main thread only. */
    private SearchIndex building;
    /** The next screen to read into it, -1 for the main screen's rows. */
    private int nextSection = -1;
    /** Counts the changes of the field, so an answer to an older query is dropped. Main thread only. */
    private int queries;
    /** The query waiting for the typing to pause. */
    private String pendingQuery;
    private SearchResults resultsBuilder;

    SettingsSearch(SettingsUi ui, Consumer<LinearLayout> mainRows, List<SectionScreen> sections) {
        this.ui = ui;
        this.activity = ui.activity;
        this.mainRows = mainRows;
        this.sections = sections;
        Map<String, SectionScreen> byId = new HashMap<>();
        for (SectionScreen section : sections) byId.put(section.id(), section);
        resultsBuilder = new SearchResults(ui, byId, this::useQuery, this::hideKeyboard);
    }

    /** A chip of the results was tapped: its words become the query. */
    private void useQuery(String query) {
        searchField.setText(query);
        searchField.setSelection(searchField.length());
    }

    /** The section rows of the main screen; they give way to the results while the search is open. */
    LinearLayout sections() {
        return searchSections;
    }

    LinearLayout results() {
        return searchResults;
    }

    /**
     * Builds the next searched screen off screen and keeps what its rows say; the main screen's rows come first.
     * One screen per call, each in a pass of the main thread of its own, so no frame waits for all of them. Done while
     * the main screen sits idle, so the first tap on the search usually finds it ready.
     *
     * @return Whether screens are left to read.
     */
    private boolean indexStep() {
        if (searchIndex != null || activity.isFinishing() || activity.isDestroyed()) return false;
        if (building == null) {
            building = new SearchIndex();
            nextSection = -1;
        }
        SearchRecorder recorder = ui.search;
        recorder.start(building);
        try {
            if (nextSection < 0) {
                // Guarded like the screens below: a failure here left the index half made, and the rows read before
                // it were read again at the next try.
                try {
                    mainRows.accept(new LinearLayout(activity));
                } catch (Exception ex) {
                    Logger.printException(() -> "Search index: could not read the main screen", ex);
                }
            } else {
                SectionScreen section = sections.get(nextSection);
                if (section.searched()) {
                    recorder.building(section);
                    try {
                        section.fill(new LinearLayout(activity));
                    } catch (Exception ex) {
                        Logger.printException(() -> "Search index: could not read the " + section.id() + " screen", ex);
                    }
                }
            }
        } finally {
            recorder.stop();
        }
        nextSection++;
        if (nextSection < sections.size()) return true;
        // Handed out only whole: the look-ups run off the main thread and must not see it grow.
        searchIndex = building;
        building = null;
        if (searchOpen && searchField != null) updateSearchResults(searchField.getText().toString());
        return false;
    }

    /** Reads the screens that are left one per message: the search is open and waits for them. */
    private final Runnable indexNow = new Runnable() {
        @Override
        public void run() {
            try {
                if (indexStep()) searchResults.post(this);
            } catch (Exception ex) {
                Logger.printException(() -> "Search index failed", ex);
            }
        }
    };

    /** Looks up the query typed last, once typing has paused. */
    private final Runnable lookUp = new Runnable() {
        @Override
        public void run() {
            final SearchIndex index = searchIndex;
            final String query = pendingQuery;
            final int generation = queries;
            if (index == null || query == null) return;
            Utils.runOnBackgroundThread(() -> {
                SearchResults.Found found;
                try {
                    found = SearchResults.find(index, query);
                } catch (Exception ex) {
                    Logger.printException(() -> "Search failed for " + query, ex);
                    return;
                }
                Utils.runOnMainThread(() -> {
                    // A later key has asked again, or the screen is gone: this answer is of no use.
                    if (generation != queries || activity.isFinishing() || searchResults == null) return;
                    if (!searchOpen && searchResults.getVisibility() != View.VISIBLE) return;
                    resultsBuilder.show(searchResults, found);
                });
            });
        }
    };

    /** The title row with the search button at its end, and the field that grows out of the button. */
    View createHeader(View title) {
        FrameLayout header = new FrameLayout(activity);
        searchSections = new SettingsPage(ui);
        searchResults = new LinearLayout(activity);
        searchResults.setOrientation(LinearLayout.VERTICAL);
        searchResults.setVisibility(View.GONE);

        searchTitleRow = title;
        int accent = ArsoundTheme.palette(activity, "special");
        searchButton = createSearchButton(accent);
        LinearLayout titleRow = (LinearLayout) title;
        titleRow.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
        titleRow.addView(searchButton, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        header.addView(titleRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        searchBar = createSearchBar(accent);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(52), Gravity.CENTER_VERTICAL);
        barParams.leftMargin = barParams.rightMargin = ui.dimen("spacing_m");
        header.addView(searchBar, barParams);
        searchBar.setVisibility(View.INVISIBLE);

        // The index is read when the main thread has nothing else to do, before anyone taps the button: after the
        // screen has come in, a screen per idle moment.
        header.postDelayed(() -> Looper.myQueue().addIdleHandler(() -> {
            try {
                return indexStep();
            } catch (Exception ex) {
                Logger.printException(() -> "Search index failed", ex);
                return false;
            }
        }), INDEX_DELAY_MS);
        return header;
    }

    private View createSearchButton(int accent) {
        ImageButton button = new ImageButton(activity);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(ArsoundTheme.palette(activity, "highlight"));
        disc.setStroke(ui.dp(1), (accent & 0x00FFFFFF) | 0x40000000);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x33000000), disc, null));
        button.setImageDrawable(new SearchGlyph(SearchGlyph.MAGNIFIER, ui.themeColor("themeColorPrimary")));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        button.setContentDescription(text("Поиск по настройкам", "Search settings"));
        button.setOnClickListener(v -> openSearch());
        return button;
    }

    private View createSearchBar(int accent) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ArsoundTheme.palette(activity, "highlight"));
        shape.setCornerRadius(ui.dp(26));
        shape.setStroke(ui.dp(1), (accent & 0x00FFFFFF) | 0x66000000);
        bar.setBackground(shape);

        ImageView lens = new ImageView(activity);
        lens.setImageDrawable(new SearchGlyph(SearchGlyph.MAGNIFIER, ArsoundTheme.palette(activity, "secondary")));
        lens.setPadding(ui.dp(14), ui.dp(14), ui.dp(10), ui.dp(14));
        bar.addView(lens, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));

        searchField = new EditText(activity);
        searchField.setBackground(null);
        searchField.setSingleLine(true);
        searchField.setHint(text("Поиск по настройкам", "Search settings"));
        searchField.setHintTextColor(ArsoundTheme.palette(activity, "secondary"));
        searchField.setTextColor(ui.themeColor("themeColorPrimary"));
        searchField.setHighlightColor((accent & 0x00FFFFFF) | 0x55000000);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT);
        searchField.setPadding(0, 0, 0, 0);
        searchField.setOnEditorActionListener((view, action, event) -> {
            hideKeyboard();
            return true;
        });
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSearchResults(s.toString());
            }
        });
        bar.addView(searchField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        ImageButton clear = new ImageButton(activity);
        clear.setImageDrawable(new SearchGlyph(SearchGlyph.CROSS, ui.themeColor("themeColorPrimary")));
        clear.setScaleType(ImageView.ScaleType.FIT_CENTER);
        clear.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        clear.setBackgroundResource(ui.themeAttribute(android.R.attr.selectableItemBackgroundBorderless));
        clear.setContentDescription(text("Закрыть поиск", "Close search"));
        // Clears what was typed; with an empty field it closes the search.
        clear.setOnClickListener(v -> {
            if (searchField.length() > 0) searchField.setText("");
            else closeSearch();
        });
        bar.addView(clear, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        return bar;
    }

    /** The field grows out of the button over the title, the sections give way to the results. */
    private void openSearch() {
        if (searchOpen) return;
        searchOpen = true;
        // The screens not read yet are read now, one per message; the results show "getting ready" meanwhile.
        if (searchIndex == null) searchResults.post(indexNow);

        int[] reveal = revealCentre();
        float far = (float) Math.hypot(Math.max(reveal[0], searchBar.getWidth() - reveal[0]), searchBar.getHeight());
        searchBar.setVisibility(View.VISIBLE);
        Animator grow = ViewAnimationUtils.createCircularReveal(
                searchBar, reveal[0], reveal[1], ui.dp(22), far);
        grow.setDuration(360);
        grow.setInterpolator(new DecelerateInterpolator(1.5f));
        grow.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!searchOpen) return;
                searchField.requestFocus();
                InputMethodManager keyboard = (InputMethodManager)
                        activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.showSoftInput(searchField, 0);
            }
        });
        cancelReveal();
        searchReveal = grow;
        grow.start();

        searchTitleRow.animate().alpha(0f).setDuration(220).start();
        searchSections.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (searchOpen) searchSections.setVisibility(View.GONE);
        }).start();
        searchResults.setAlpha(0f);
        searchResults.setVisibility(View.VISIBLE);
        updateSearchResults(searchField.getText().toString());
        searchResults.animate().alpha(1f).setStartDelay(120).setDuration(240).start();
    }

    private void closeSearch() {
        if (!searchOpen) return;
        searchOpen = false;
        hideKeyboard();

        int[] reveal = revealCentre();
        float far = (float) Math.hypot(Math.max(reveal[0], searchBar.getWidth() - reveal[0]), searchBar.getHeight());
        Animator shrink = ViewAnimationUtils.createCircularReveal(
                searchBar, reveal[0], reveal[1], far, ui.dp(22));
        shrink.setDuration(280);
        shrink.setInterpolator(new AccelerateDecelerateInterpolator());
        shrink.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (searchOpen) return;
                searchBar.setVisibility(View.INVISIBLE);
                searchField.setText("");
            }
        });
        cancelReveal();
        searchReveal = shrink;
        shrink.start();

        searchTitleRow.animate().alpha(1f).setStartDelay(140).setDuration(240).start();
        searchResults.animate().alpha(0f).setStartDelay(0).setDuration(150).withEndAction(() -> {
            if (searchOpen) return;
            searchResults.setVisibility(View.GONE);
            searchResults.removeAllViews();
            resultsBuilder.reset();
        }).start();
        searchSections.setAlpha(0f);
        searchSections.setVisibility(View.VISIBLE);
        searchSections.animate().alpha(1f).setStartDelay(120).setDuration(260).start();
    }

    /** A quick tap on the button again must not run the growing and the shrinking of the field at once. */
    private void cancelReveal() {
        if (searchReveal != null) searchReveal.cancel();
        searchReveal = null;
    }

    /** The middle of the search button, in the coordinates of the field: where the field starts to grow. */
    private int[] revealCentre() {
        int[] button = new int[2];
        int[] bar = new int[2];
        searchButton.getLocationInWindow(button);
        searchBar.getLocationInWindow(bar);
        int x = button[0] + searchButton.getWidth() / 2 - bar[0];
        int y = button[1] + searchButton.getHeight() / 2 - bar[1];
        return new int[]{Math.max(0, Math.min(searchBar.getWidth(), x)), Math.max(0, Math.min(searchBar.getHeight(), y))};
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null && searchField != null) keyboard.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
    }

    /** Closes the search if it is open; true if it was, so the back press is spent. */
    boolean closeIfOpen() {
        if (!searchOpen) return false;
        closeSearch();
        return true;
    }

    /**
     * The field changed. The look-up waits for a pause in the typing and runs off the main thread; an empty field and
     * an index not ready yet are answered at once.
     */
    private void updateSearchResults(String query) {
        if (searchResults == null || !searchOpen && searchResults.getVisibility() != View.VISIBLE) return;
        queries++;
        searchResults.removeCallbacks(lookUp);
        if (query.trim().isEmpty()) {
            pendingQuery = null;
            resultsBuilder.showStart(searchResults);
            return;
        }
        pendingQuery = query;
        if (searchIndex == null) {
            resultsBuilder.showPreparing(searchResults);
            return;
        }
        searchResults.postDelayed(lookUp, TYPING_PAUSE_MS);
    }
}
