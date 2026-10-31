package app.revanced.extension.soundcloud.search;

import static app.revanced.extension.soundcloud.shared.Colors.withAlpha;
import static app.revanced.extension.soundcloud.shared.Dimens.dpTruncated;
import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    /** How long the tracks wait, at most, for the albums and the artists found beside them. */
    private static final long EXTRA_WAIT_MS = 20_000;
    /** Rows of an album added per frame: an album may have 500 tracks, each row a few views. */
    private static final int ROWS_PER_STEP = 30;

    /** The screen that is on show: previews and downloads redraw its rows. */
    private static WeakReference<SearchScreen> current = new WeakReference<>(null);
    /** Which half of the switch is selected. Kept when the tab is left and opened again. */
    private static boolean arsoundSelected;

    final Context context;
    final int textColor;
    /** SoundCloud's look for the rows, so both halves of the switch read as one app. */
    final SearchLook look;
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
        look = new SearchLook(context, textColor);

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
        if (!arsound) {
            PreviewPlayer.stop();
            // A search waiting for the typing to pause is for the Arsound half, which is left now.
            SearchThreads.MAIN.removeCallbacks(searchLater);
        }
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
        // A search posted while the Arsound half was on, run after it was left.
        if (!arsoundSelected) return;
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
        // One time limit for both, counted from now: one after the other, a stuck network held the tracks 40 s.
        long deadline = System.currentTimeMillis() + EXTRA_WAIT_MS;
        List<OtherSource.Album> foundAlbums = resultOrEmpty(albums, deadline);
        List<OtherSource.Artist> foundArtists = resultOrEmpty(artists, deadline);
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

    private static <T> List<T> resultOrEmpty(Future<List<T>> future, long deadline) {
        try {
            // The tracks wait for this: a stuck lookup must not hold them for ever.
            return future.get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
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
                list.addView(entityRow(artist.name, text("Исполнитель", "Artist"), null, artist.thumbUrl, true,
                        "ic_actions_user", () -> open(() -> showArtist(artist))));
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
        list.addView(entityRow(album.title, album.artist, albumDetails(album), album.thumbUrl, false,
                "ic_actions_playlist", () -> open(() -> showAlbum(album))));
    }

    /** "Album · 12 tracks", as the details line of SoundCloud's playlist cells. */
    private static String albumDetails(OtherSource.Album album) {
        String kind = text("Альбом", "Album");
        return album.trackCount > 0 ? kind + " · " + tracks(album.trackCount) : kind;
    }

    private static String tracks(long count) {
        if (!app.revanced.extension.soundcloud.shared.Lang.russian()) return count + (count == 1 ? " track" : " tracks");
        long tens = count % 100, ones = count % 10;
        String word = tens >= 11 && tens <= 14 ? "треков" : ones == 1 ? "трек" : ones >= 2 && ones <= 4 ? "трека" : "треков";
        return count + " " + word;
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

    /**
     * Shows the top of a page (back, the big cover or avatar, the name), then loads the page content off the main
     * thread.
     */
    private <T> void loadPage(Runnable self, String title, String subtitle, String thumb, boolean round, String icon,
                              Callable<T> load, Consumer<T> show) {
        shownPage = self;
        int current = ++generation;
        int token = ++pageToken;
        clearList();
        list.addView(backRow());
        list.addView(hero(title, subtitle, thumb, round, icon));
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
        loadPage(() -> showArtist(artist), artist.name, text("Исполнитель", "Artist"), artist.thumbUrl, true,
                "ic_actions_user", () -> OtherSource.artistAlbums(artist), albums -> {
                    if (albums.isEmpty()) {
                        list.addView(note(text("Альбомов не нашлось.", "No albums found.")));
                        return;
                    }
                    list.addView(header(text("Альбомы и синглы", "Albums and singles")));
                    for (OtherSource.Album album : albums) addAlbumRow(album);
                });
    }

    private void showAlbum(OtherSource.Album album) {
        String subtitle = album.artist.isEmpty() ? albumDetails(album) : album.artist + " · " + albumDetails(album);
        loadPage(() -> showAlbum(album), album.title, subtitle, album.thumbUrl, false, "ic_actions_playlist",
                () -> OtherSource.albumTracks(album), tracks -> {
                    if (tracks.isEmpty()) {
                        list.addView(note(text("В альбоме нет треков.", "The album has no tracks.")));
                        return;
                    }
                    // Only the tracks of this album: the count on the button has to match what is downloaded.
                    list.addView(downloadAllButton(tracks));
                    addTracksInSteps(tracks, 0, generation);
                });
    }

    /** "Download all (N)" under the cover of an album page, and where the tracks go. */
    private View downloadAllButton(List<OtherSource.Track> tracks) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(look.dp(16), look.dp(4), look.dp(16), look.dp(12));
        box.addView(look.pill(text("Скачать всё", "Download all") + " (" + tracks.size() + ")",
                () -> DownloadQueue.askToStartAll(context, tracks)));
        TextView where = look.meta(text("В плейлист «Импортированные»", "To the imported music"));
        where.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = look.dp(6);
        box.addView(where, params);
        return box;
    }

    /** Adds the rows a few per frame, so a long album does not stop the screen while it builds them. */
    private void addTracksInSteps(List<OtherSource.Track> tracks, int from, int current) {
        if (current != generation) return;
        int to = Math.min(tracks.size(), from + ROWS_PER_STEP);
        addTracks(tracks.subList(from, to));
        if (to < tracks.size()) list.post(() -> addTracksInSteps(tracks, to, current));
    }

    // endregion

    // region Rows of the list

    private TextView header(String label) {
        return look.header(label);
    }

    private TextView note(String label) {
        return look.note(label);
    }

    /**
     * A row that opens a page, as SoundCloud's artist and playlist cells: the picture, the name, a grey line, the
     * details and an arrow.
     */
    private LinearLayout entityRow(String title, String subtitle, String details, String thumb, boolean round, String icon,
                                   Runnable onClick) {
        LinearLayout row = look.row();
        row.setOnClickListener(v -> onClick.run());
        ImageView art = look.artwork(round, icon);
        look.load(art, thumb, SearchLook.ART_DP);
        TextView second = subtitle == null || subtitle.isEmpty() ? null : look.subtitle(subtitle);
        TextView third = details == null || details.isEmpty() ? null : look.meta(details);
        look.fill(row, art, look.texts(look.title(title), second, third), look.chevron());
        return row;
    }

    /** The back arrow at the top of a page opened from the results. */
    private View backRow() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(look.dp(4), look.dp(4), look.dp(16), 0);
        ImageView arrow = new ImageView(context);
        int icon = app.revanced.extension.shared.Utils.getResourceIdentifier(
                app.revanced.extension.shared.ResourceType.DRAWABLE, "ic_actions_back_primary");
        if (icon != 0) arrow.setImageResource(icon);
        arrow.setImageTintList(ColorStateList.valueOf(look.primary));
        arrow.setScaleType(ImageView.ScaleType.CENTER);
        arrow.setContentDescription(text("Назад", "Back"));
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        arrow.setBackgroundResource(ripple.resourceId);
        arrow.setOnClickListener(v -> goBack());
        row.addView(arrow, new LinearLayout.LayoutParams(look.dp(48), look.dp(48)));
        return row;
    }

    /** The top of an album or artist page: the big cover (a circle for an artist), the name and a grey line. */
    private View hero(String title, String subtitle, String thumb, boolean round, String icon) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(look.dp(24), 0, look.dp(24), look.dp(12));
        ImageView art = look.artwork(round, icon);
        look.load(art, thumb, SearchLook.HERO_DP);
        box.addView(art, new LinearLayout.LayoutParams(look.dp(SearchLook.HERO_DP), look.dp(SearchLook.HERO_DP)));
        TextView name = look.title(title);
        int h2 = app.revanced.extension.shared.Utils.getResourceIdentifier(
                app.revanced.extension.shared.ResourceType.STYLE, "H2.Primary");
        if (h2 != 0) name.setTextAppearance(h2);
        name.setTextColor(look.primary);
        name.setSingleLine(false);
        name.setMaxLines(2);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = look.dp(16);
        box.addView(name, nameParams);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView line = look.subtitle(subtitle);
            line.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lineParams.topMargin = look.dp(4);
            box.addView(line, lineParams);
        }
        return box;
    }

    // endregion
}
