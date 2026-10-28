package app.revanced.extension.soundcloud.search;

import static app.revanced.extension.soundcloud.shared.Colors.withAlpha;
import static app.revanced.extension.soundcloud.shared.Dimens.dpTruncated;
import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The search screen with the switch and the Arsound results: the switch and the tabs, the list of results and the
 * pages opened from it (an artist's albums, the tracks of an album). The SoundCloud half is SoundCloud's own view,
 * which this screen hides while Arsound is selected.
 */
final class SearchScreen {
    private static final long SEARCH_DELAY_MS = 700;

    /** The screen that is on show: previews and downloads redraw its rows. */
    private static WeakReference<SearchScreen> current = new WeakReference<>(null);
    /** Which half of the switch is selected. Kept when the tab is left and opened again. */
    private static boolean arsoundSelected;

    final Context context;
    final int textColor;
    private final View soundCloudResults;
    private final EditText edit;
    private final SourceToggle toggle;
    private final ResultTabs tabs;
    private final FrameLayout results;
    private final LinearLayout list;
    private final TextView status;
    private final ProgressBar spinner;
    private final Map<String, ResultRow> rows = new HashMap<>();
    private final Runnable searchLater = this::search;
    private String shownQuery;
    /** Changes with every search and page load; background work of an older one is dropped. */
    private volatile int generation;
    /** Changes when the user goes back or changes the tab, which drops a page that is still loading. */
    private volatile int pageToken;
    /** Pages opened from the results: the page to return to is on top. */
    private final ArrayDeque<Runnable> back = new ArrayDeque<>();
    /** Shows the results of the search, for the tabs to show again. Null until there are some. */
    private Runnable resultsPage;
    /** Shows the page that is on screen now. */
    private Runnable shownPage;

    SearchScreen(LinearLayout coordinator, View soundCloudResults, EditText edit) {
        context = coordinator.getContext();
        this.soundCloudResults = soundCloudResults;
        this.edit = edit;
        textColor = edit.getCurrentTextColor();

        toggle = new SourceToggle(context, textColor, this::select);
        coordinator.addView(toggle.view(), 1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        results = new FrameLayout(context);
        ScrollView scroll = new ScrollView(context);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        // Room for the mini player and the tab bar over the end of the list.
        list.setPadding(0, 0, 0, dpTruncated(context, 160));
        scroll.addView(list);
        results.addView(scroll);
        status = new TextView(context);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(withAlpha(textColor, 0xB0));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setPadding(dpTruncated(context, 32), dpTruncated(context, 48), dpTruncated(context, 32), 0);
        results.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        spinner = new ProgressBar(context);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(accent(context)));
        FrameLayout.LayoutParams spinnerParams = new FrameLayout.LayoutParams(dpTruncated(context, 36), dpTruncated(context, 36),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        spinnerParams.topMargin = dpTruncated(context, 48);
        results.addView(spinner, spinnerParams);

        tabs = new ResultTabs(context, textColor, this::onTabChanged);
        coordinator.addView(tabs.view(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        coordinator.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                SearchThreads.MAIN.removeCallbacks(searchLater);
                if (arsoundSelected) SearchThreads.MAIN.postDelayed(searchLater, SEARCH_DELAY_MS);
            }
        });

        apply();
    }

    /** The accent of the chosen theme, SoundCloud orange in the original look. */
    static int accent(Context context) {
        return ArsoundTheme.palette(context, "special");
    }

    static void makeCurrent(SearchScreen screen) {
        current = new WeakReference<>(screen);
    }

    /** Redraws every row: a preview started or stopped. */
    static void refreshRows() {
        SearchScreen screen = current.get();
        if (screen != null) screen.refreshAllRows();
    }

    /** Redraws one row: a download tick or an end must not redraw the whole list. */
    static void refreshRow(String url) {
        SearchScreen screen = current.get();
        ResultRow row = screen == null ? null : screen.rows.get(url);
        if (row != null) row.refresh();
    }

    private void refreshAllRows() {
        for (ResultRow row : rows.values()) row.refresh();
    }

    private void select(boolean arsound) {
        if (arsoundSelected == arsound) return;
        arsoundSelected = arsound;
        if (!arsound) PreviewPlayer.stop();
        apply();
    }

    /** Shows the results of the selected source. */
    private void apply() {
        toggle.showSelection(arsoundSelected);
        soundCloudResults.setVisibility(arsoundSelected ? View.GONE : View.VISIBLE);
        results.setVisibility(arsoundSelected ? View.VISIBLE : View.GONE);
        tabs.view().setVisibility(arsoundSelected ? View.VISIBLE : View.GONE);
        if (arsoundSelected) search();
    }

    private void onTabChanged() {
        // Opened albums and artists belong to the results of the previous tab.
        back.clear();
        // A page that is still loading belongs to the previous tab.
        pageToken++;
        if (resultsPage != null) resultsPage.run();
    }

    // region Search

    private void search() {
        String query = edit.getText().toString().trim();
        if (query.equals(shownQuery)) return;
        shownQuery = query;
        int current = ++generation;
        // The tabs must not bring back the results of the previous query.
        resultsPage = null;
        shownPage = null;
        list.removeAllViews();
        rows.clear();
        if (query.isEmpty()) {
            spinner.setVisibility(View.GONE);
            status.setVisibility(View.VISIBLE);
            status.setText(text("Введите трек, альбом или исполнителя — найдём то, что не скачивается в SoundCloud.",
                    "Type a track, album or artist to find what SoundCloud does not let download."));
            return;
        }
        back.clear();
        status.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        SearchThreads.NETWORK.execute(() -> find(query, current));
    }

    /** Searches tracks, albums and artists on a network thread and shows what was found. */
    private void find(String query, int current) {
        // Typed over while waiting in the queue: the three requests are not needed any more.
        if (current != generation) return;
        // Albums and artists are asked for at the same time as tracks; their failure only hides them.
        Future<List<OtherSource.Album>> albums = SearchThreads.EXTRA.submit(() -> {
            if (current != generation) return new ArrayList<OtherSource.Album>();
            return OtherSource.searchAlbums(query);
        });
        Future<List<OtherSource.Artist>> artists = SearchThreads.EXTRA.submit(() -> {
            if (current != generation) return new ArrayList<OtherSource.Artist>();
            return OtherSource.searchArtists(query);
        });
        List<OtherSource.Track> found = null;
        Exception error = null;
        try {
            found = OtherSource.search(query);
        } catch (Exception ex) {
            error = ex;
            Logger.printException(() -> "Arsound search failed", ex);
        }
        if (current != generation) {
            // Replaced while the tracks were searched: free this thread without waiting for the rest.
            albums.cancel(true);
            artists.cancel(true);
            return;
        }
        List<OtherSource.Album> foundAlbums = resultOrEmpty(albums);
        List<OtherSource.Artist> foundArtists = resultOrEmpty(artists);
        List<OtherSource.Track> tracks = error != null ? new ArrayList<>() : found;
        boolean failed = error != null;
        boolean blocked = SearchFailure.isRegionBlock(error);
        SearchThreads.MAIN.post(() -> {
            if (current != generation) return;
            spinner.setVisibility(View.GONE);
            if (tracks.isEmpty() && foundAlbums.isEmpty() && foundArtists.isEmpty()) {
                status.setVisibility(View.VISIBLE);
                status.setText(blocked
                        ? SearchFailure.regionBlockedText() + text(" (Настройки → Arsound → сеть)", " (Settings → Arsound → network)")
                        : failed
                        ? text("Не получилось найти: проверьте интернет.", "Search failed: check the connection.")
                        : text("Ничего не нашлось.", "Nothing found."));
                if (failed) shownQuery = null;
                return;
            }
            resultsPage = () -> showResults(foundArtists, foundAlbums, tracks);
            resultsPage.run();
        });
    }

    private static <T> List<T> resultOrEmpty(Future<List<T>> future) {
        try {
            // The tracks wait for this: a stuck lookup must not hold them for ever.
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception ex) {
            future.cancel(true);
            Logger.printException(() -> "Arsound album or artist search failed", ex);
            return new ArrayList<>();
        }
    }

    // endregion

    // region Pages

    private void clearList() {
        list.removeAllViews();
        rows.clear();
        status.setVisibility(View.GONE);
        spinner.setVisibility(View.GONE);
    }

    private void showResults(List<OtherSource.Artist> artists, List<OtherSource.Album> albums, List<OtherSource.Track> tracks) {
        shownPage = resultsPage;
        clearList();
        // "All" shows a few of each; a tab shows everything of its kind.
        int tab = tabs.selected();
        boolean all = tab == ResultTabs.ALL;
        if ((all || tab == ResultTabs.ARTISTS) && !artists.isEmpty()) {
            if (all) list.addView(header(text("Исполнители", "Artists")));
            for (int i = 0; i < (all ? Math.min(3, artists.size()) : artists.size()); i++) {
                OtherSource.Artist artist = artists.get(i);
                list.addView(linkRow(artist.name, text("Исполнитель · альбомы", "Artist · albums"),
                        () -> open(() -> showArtist(artist))));
            }
        }
        if ((all || tab == ResultTabs.ALBUMS) && !albums.isEmpty()) {
            if (all) list.addView(header(text("Альбомы", "Albums")));
            for (int i = 0; i < (all ? Math.min(6, albums.size()) : albums.size()); i++) addAlbumRow(albums.get(i));
        }
        if ((all || tab == ResultTabs.TRACKS) && !tracks.isEmpty()) {
            if (all && (!artists.isEmpty() || !albums.isEmpty())) list.addView(header(text("Треки", "Tracks")));
            addTracks(tracks);
        }
        if (list.getChildCount() == 0) list.addView(note(text("Здесь ничего не нашлось.", "Nothing here.")));
    }

    private void addAlbumRow(OtherSource.Album album) {
        String count = album.trackCount > 0 ? album.trackCount + " " + text("тр.", "tracks") : "";
        String subtitle = album.artist.isEmpty() ? count : count.isEmpty() ? album.artist : album.artist + " · " + count;
        list.addView(linkRow(album.title, subtitle, () -> open(() -> showAlbum(album))));
    }

    /** Adds a row for each track and returns the rows that were added. */
    private List<ResultRow> addTracks(List<OtherSource.Track> tracks) {
        List<ResultRow> added = new ArrayList<>(tracks.size());
        for (OtherSource.Track track : tracks) {
            ResultRow row = new ResultRow(this, track);
            rows.put(track.url, row);
            list.addView(row.view());
            added.add(row);
        }
        return added;
    }

    /** Opens a page; the back row returns to the page shown now. */
    private void open(Runnable page) {
        if (shownPage != null) back.push(shownPage);
        page.run();
    }

    private void goBack() {
        Runnable previous = back.poll();
        if (previous == null) return;
        // A page that is still loading is left behind; a page opened again takes a new token.
        pageToken++;
        previous.run();
    }

    /** Shows a page title with a back row, then loads the page content off the main thread. */
    private <T> void loadPage(Runnable self, String title, Callable<T> load, Consumer<T> show) {
        shownPage = self;
        int current = ++generation;
        int token = ++pageToken;
        clearList();
        list.addView(linkRow("←  " + text("Назад", "Back"), null, this::goBack));
        list.addView(header(title));
        spinner.setVisibility(View.VISIBLE);
        SearchThreads.NETWORK.execute(() -> {
            // Replaced or left while waiting in the queue: nothing to load.
            if (current != generation || token != pageToken) return;
            T value = null;
            Exception error = null;
            try {
                value = load.call();
            } catch (Exception ex) {
                error = ex;
                Logger.printException(() -> "Could not open " + title, ex);
            }
            T loaded = value;
            boolean failed = error != null;
            SearchThreads.MAIN.post(() -> {
                if (current != generation || token != pageToken) return;
                spinner.setVisibility(View.GONE);
                if (failed) {
                    list.addView(note(text("Не получилось открыть: проверьте интернет.",
                            "Could not open: check the connection.")));
                    return;
                }
                show.accept(loaded);
            });
        });
    }

    private void showArtist(OtherSource.Artist artist) {
        loadPage(() -> showArtist(artist), artist.name, () -> OtherSource.artistAlbums(artist), albums -> {
            if (albums.isEmpty()) list.addView(note(text("Альбомов не нашлось.", "No albums found.")));
            for (OtherSource.Album album : albums) addAlbumRow(album);
        });
    }

    private void showAlbum(OtherSource.Album album) {
        loadPage(() -> showAlbum(album), album.title, () -> OtherSource.albumTracks(album), tracks -> {
            if (tracks.isEmpty()) {
                list.addView(note(text("В альбоме нет треков.", "The album has no tracks.")));
                return;
            }
            // Only the tracks of this album: the count in the row has to match what is downloaded.
            List<ResultRow> albumRows = new ArrayList<>();
            list.addView(linkRow(text("Скачать всё", "Download all") + " (" + tracks.size() + ")",
                    text("В плейлист «Импортированные»", "To the imported music"), () -> {
                        for (ResultRow row : albumRows) DownloadQueue.start(context.getApplicationContext(), row.track());
                    }));
            albumRows.addAll(addTracks(tracks));
        });
    }

    // endregion

    // region Rows of the list

    private TextView header(String label) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(textColor);
        view.setPadding(dpTruncated(context, 16), dpTruncated(context, 16), dpTruncated(context, 16), dpTruncated(context, 4));
        return view;
    }

    private TextView note(String label) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTextColor(withAlpha(textColor, 0xB0));
        view.setPadding(dpTruncated(context, 16), dpTruncated(context, 16), dpTruncated(context, 16), dpTruncated(context, 16));
        return view;
    }

    /** A row that opens something: a title and an optional grey line under it. */
    private LinearLayout linkRow(String title, String subtitle, Runnable onClick) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinimumHeight(dpTruncated(context, 56));
        view.setPadding(dpTruncated(context, 16), dpTruncated(context, 8), dpTruncated(context, 16), dpTruncated(context, 8));
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        view.setBackgroundResource(ripple.resourceId);
        view.setOnClickListener(v -> onClick.run());
        TextView first = new TextView(context);
        first.setText(title);
        first.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        first.setTextColor(textColor);
        first.setSingleLine(true);
        first.setEllipsize(TextUtils.TruncateAt.END);
        view.addView(first);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView second = new TextView(context);
            second.setText(subtitle);
            second.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            second.setTextColor(withAlpha(textColor, 0xA0));
            second.setSingleLine(true);
            second.setEllipsize(TextUtils.TruncateAt.END);
            view.addView(second);
        }
        return view;
    }

    // endregion
}
