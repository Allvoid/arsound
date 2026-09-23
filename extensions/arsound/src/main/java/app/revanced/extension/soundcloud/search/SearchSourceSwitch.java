package app.revanced.extension.soundcloud.search;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.arsound.shaded.newpipe.extractor.stream.AudioStream;
import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.local.LocalMusic;
import app.revanced.extension.soundcloud.permissions.WelcomePermissions;

/**
 * A switch under the search field of the Search tab: SoundCloud (the usual search) or Arsound.
 * Arsound searches YouTube Music for tracks that SoundCloud does not let download. A result can be
 * listened to with a tap and downloaded with the button on the right; downloads go to the imported music.
 */
public final class SearchSourceSwitch {
    private static final String TAG = "arsound_search_switch";
    private static final long SEARCH_DELAY_MS = 700;
    private static final int ACCENT = 0xFFFF5500;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final ExecutorService network = Executors.newFixedThreadPool(3);
    /** Album and artist searches, run next to the track search without taking its threads. */
    private static final ExecutorService extra = Executors.newFixedThreadPool(2);
    /** Track URL → download percent, while downloading. Survives leaving and opening the tab again. */
    private static final Map<String, Integer> downloading = new HashMap<>();
    private static final Set<String> downloaded = new HashSet<>();

    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private static boolean arsoundSelected;
    private static WeakReference<Screen> screen = new WeakReference<>(null);

    private static MediaPlayer player;
    private static String playingUrl;
    private static boolean preparing;
    private static AudioFocusRequest focusRequest;

    private SearchSourceSwitch() {
    }

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }

    private static int id(String name) {
        return Utils.getResourceIdentifier(ResourceType.ID, name);
    }

    /** Called when an activity comes to the screen. The search screen lives in the main activity. */
    public static void onActivityResumed(Activity resumed) {
        if (!resumed.getClass().getName().endsWith(".MainActivity")) return;
        activity = new WeakReference<>(resumed);
        View decor = resumed.getWindow().getDecorView();
        if (layoutListener != null) decor.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        layoutListener = () -> attach(decor);
        decor.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        attach(decor);
    }

    public static void onActivityPaused(Activity paused) {
        if (activity.get() == paused) stopPreview();
    }

    /** Adds the switch to the search screen if it is shown and has no switch yet. */
    private static void attach(View decor) {
        try {
            View found = decor.findViewById(id("search_coordinator"));
            if (!(found instanceof LinearLayout)) return;
            LinearLayout coordinator = (LinearLayout) found;
            if (coordinator.findViewWithTag(TAG) != null) return;
            View container = coordinator.findViewById(id("search_container"));
            View edit = coordinator.findViewById(id("search_edit_text"));
            if (container == null || !(edit instanceof EditText)) return;
            Screen created = new Screen(coordinator, container, (EditText) edit);
            screen = new WeakReference<>(created);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add the search switch", ex);
        }
    }

    /** The switch and the Arsound results of one search screen. */
    private static final class Screen {
        final Context context;
        final View soundCloudResults;
        final EditText edit;
        final int textColor;
        final TextView soundCloudButton;
        final TextView arsoundButton;
        final LinearLayout arsoundSegment;
        final TextView help;
        final FrameLayout results;
        final LinearLayout list;
        final TextView status;
        final ProgressBar spinner;
        final Map<String, Row> rows = new HashMap<>();
        String shownQuery;
        int generation;

        Screen(LinearLayout coordinator, View soundCloudResults, EditText edit) {
            context = coordinator.getContext();
            this.soundCloudResults = soundCloudResults;
            this.edit = edit;
            textColor = edit.getCurrentTextColor();

            LinearLayout bar = new LinearLayout(context);
            bar.setTag(TAG);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            // Same side margin as the search field; the help button sits under the cast button.

            LinearLayout toggle = new LinearLayout(context);
            toggle.setOrientation(LinearLayout.HORIZONTAL);
            toggle.setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3));
            GradientDrawable frame = new GradientDrawable();
            frame.setCornerRadius(dp(context, 12));
            frame.setColor(withAlpha(textColor, 0x1A));
            toggle.setBackground(frame);
            soundCloudButton = segment("SoundCloud");
            arsoundButton = segment("Arsound");
            toggle.addView(soundCloudButton, new LinearLayout.LayoutParams(0, dp(context, 38), 1));
            soundCloudButton.setOnClickListener(v -> select(false));

            // The Arsound half holds its name and a small "?" that explains the search.
            arsoundSegment = new LinearLayout(context);
            arsoundSegment.setOrientation(LinearLayout.HORIZONTAL);
            arsoundSegment.setGravity(Gravity.CENTER);
            arsoundSegment.setOnClickListener(v -> select(true));
            arsoundSegment.addView(arsoundButton);
            help = new TextView(context);
            help.setText("?");
            help.setGravity(Gravity.CENTER);
            help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            help.setTypeface(Typeface.DEFAULT_BOLD);
            help.setIncludeFontPadding(false);
            help.setContentDescription(text("Что такое поиск Arsound", "What Arsound search is"));
            help.setOnClickListener(v -> showHelp(context));
            LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(dp(context, 17), dp(context, 17));
            helpParams.leftMargin = dp(context, 6);
            arsoundSegment.addView(help, helpParams);
            toggle.addView(arsoundSegment, new LinearLayout.LayoutParams(0, dp(context, 38), 1));
            bar.setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 8));
            bar.addView(toggle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            coordinator.addView(bar, 1, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            results = new FrameLayout(context);
            ScrollView scroll = new ScrollView(context);
            list = new LinearLayout(context);
            list.setOrientation(LinearLayout.VERTICAL);
            // Room for the mini player and the tab bar over the end of the list.
            list.setPadding(0, 0, 0, dp(context, 160));
            scroll.addView(list);
            results.addView(scroll);
            status = new TextView(context);
            status.setGravity(Gravity.CENTER);
            status.setTextColor(withAlpha(textColor, 0xB0));
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            status.setPadding(dp(context, 32), dp(context, 48), dp(context, 32), 0);
            results.addView(status, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
            spinner = new ProgressBar(context);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
            FrameLayout.LayoutParams spinnerParams = new FrameLayout.LayoutParams(dp(context, 36), dp(context, 36),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            spinnerParams.topMargin = dp(context, 48);
            results.addView(spinner, spinnerParams);
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
                    handler.removeCallbacks(searchLater);
                    if (arsoundSelected) handler.postDelayed(searchLater, SEARCH_DELAY_MS);
                }
            });

            apply();
        }

        final Runnable searchLater = this::search;

        TextView segment(String label) {
            TextView view = new TextView(context);
            view.setText(label);
            view.setGravity(Gravity.CENTER);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            view.setTypeface(Typeface.DEFAULT_BOLD);
            return view;
        }

        void select(boolean arsound) {
            if (arsoundSelected == arsound) return;
            arsoundSelected = arsound;
            if (!arsound) stopPreview();
            apply();
        }

        /** Shows the results of the selected source. */
        void apply() {
            style(soundCloudButton, soundCloudButton, !arsoundSelected);
            style(arsoundSegment, arsoundButton, arsoundSelected);
            int helpColor = arsoundSelected ? inverse(textColor) : withAlpha(textColor, 0xB0);
            help.setTextColor(helpColor);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setStroke(dp(context, 1.2f), helpColor);
            help.setBackground(circle);
            soundCloudResults.setVisibility(arsoundSelected ? View.GONE : View.VISIBLE);
            results.setVisibility(arsoundSelected ? View.VISIBLE : View.GONE);
            if (arsoundSelected) search();
        }

        void style(View segment, TextView label, boolean selected) {
            GradientDrawable fill = new GradientDrawable();
            fill.setCornerRadius(dp(context, 10));
            fill.setColor(selected ? withAlpha(textColor, 0xFF) : Color.TRANSPARENT);
            segment.setBackground(fill);
            // The selected half is filled with the text color, so its label takes the page color.
            label.setTextColor(selected ? inverse(textColor) : withAlpha(textColor, 0xB0));
        }

        void search() {
            String query = edit.getText().toString().trim();
            if (query.equals(shownQuery)) return;
            shownQuery = query;
            int current = ++generation;
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
            network.execute(() -> {
                // Albums and artists are asked for at the same time as tracks; their failure only hides them.
                java.util.concurrent.Future<List<OtherSource.Album>> albums =
                        extra.submit(() -> OtherSource.searchAlbums(query));
                java.util.concurrent.Future<List<OtherSource.Artist>> artists =
                        extra.submit(() -> OtherSource.searchArtists(query));
                List<OtherSource.Track> found = null;
                Exception error = null;
                try {
                    found = OtherSource.search(query);
                } catch (Exception ex) {
                    error = ex;
                    Logger.printException(() -> "Arsound search failed", ex);
                }
                List<OtherSource.Album> foundAlbums = resultOrEmpty(albums);
                List<OtherSource.Artist> foundArtists = resultOrEmpty(artists);
                List<OtherSource.Track> tracks = error != null ? new java.util.ArrayList<>() : found;
                boolean failed = error != null;
                boolean blocked = isRegionBlock(error);
                handler.post(() -> {
                    if (current != generation) return;
                    spinner.setVisibility(View.GONE);
                    if (tracks.isEmpty() && foundAlbums.isEmpty() && foundArtists.isEmpty()) {
                        status.setVisibility(View.VISIBLE);
                        status.setText(blocked
                                ? text("Российский IP — поиск Arsound отключён (Настройки → Arsound → сеть).",
                                "Russian IP: the Arsound search is off (Settings → Arsound → network).")
                                : failed
                                ? text("Не получилось найти: проверьте интернет.", "Search failed: check the connection.")
                                : text("Ничего не нашлось.", "Nothing found."));
                        if (failed) shownQuery = null;
                        return;
                    }
                    root = () -> showResults(foundArtists, foundAlbums, tracks);
                    root.run();
                });
            });
        }

        /** Pages opened from the results: the page to return to is on top. */
        final java.util.ArrayDeque<Runnable> back = new java.util.ArrayDeque<>();
        Runnable root;
        Runnable shown;

        void clearList() {
            list.removeAllViews();
            rows.clear();
            status.setVisibility(View.GONE);
            spinner.setVisibility(View.GONE);
        }

        void showResults(List<OtherSource.Artist> artists, List<OtherSource.Album> albums, List<OtherSource.Track> tracks) {
            shown = root;
            clearList();
            if (!artists.isEmpty()) {
                list.addView(header(text("Исполнители", "Artists")));
                for (int i = 0; i < Math.min(3, artists.size()); i++) {
                    OtherSource.Artist artist = artists.get(i);
                    list.addView(linkRow(artist.name, text("Исполнитель · альбомы", "Artist · albums"),
                            () -> open(() -> showArtist(artist))));
                }
            }
            if (!albums.isEmpty()) {
                list.addView(header(text("Альбомы", "Albums")));
                for (int i = 0; i < Math.min(6, albums.size()); i++) addAlbumRow(albums.get(i));
            }
            if (!tracks.isEmpty()) {
                if (!artists.isEmpty() || !albums.isEmpty()) list.addView(header(text("Треки", "Tracks")));
                addTracks(tracks);
            }
        }

        void addAlbumRow(OtherSource.Album album) {
            String count = album.trackCount > 0 ? album.trackCount + " " + text("тр.", "tracks") : "";
            String subtitle = album.artist.isEmpty() ? count : count.isEmpty() ? album.artist : album.artist + " · " + count;
            list.addView(linkRow(album.title, subtitle, () -> open(() -> showAlbum(album))));
        }

        void addTracks(List<OtherSource.Track> tracks) {
            for (OtherSource.Track track : tracks) {
                Row row = new Row(this, track);
                rows.put(track.url, row);
                list.addView(row.view);
            }
        }

        /** Opens a page; the back row returns to the page shown now. */
        void open(Runnable page) {
            if (shown != null) back.push(shown);
            page.run();
        }

        void goBack() {
            Runnable previous = back.poll();
            if (previous != null) previous.run();
        }

        /** Shows a page title with a back row, then loads the page content off the main thread. */
        <T> void loadPage(Runnable self, String title, java.util.concurrent.Callable<T> load,
                          java.util.function.Consumer<T> show) {
            shown = self;
            int current = ++generation;
            clearList();
            list.addView(linkRow("←  " + text("Назад", "Back"), null, this::goBack));
            list.addView(header(title));
            spinner.setVisibility(View.VISIBLE);
            network.execute(() -> {
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
                handler.post(() -> {
                    if (current != generation) return;
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

        void showArtist(OtherSource.Artist artist) {
            loadPage(() -> showArtist(artist), artist.name, () -> OtherSource.artistAlbums(artist), albums -> {
                if (albums.isEmpty()) list.addView(note(text("Альбомов не нашлось.", "No albums found.")));
                for (OtherSource.Album album : albums) addAlbumRow(album);
            });
        }

        void showAlbum(OtherSource.Album album) {
            loadPage(() -> showAlbum(album), album.title, () -> OtherSource.albumTracks(album), tracks -> {
                if (tracks.isEmpty()) {
                    list.addView(note(text("В альбоме нет треков.", "The album has no tracks.")));
                    return;
                }
                list.addView(linkRow(text("Скачать всё", "Download all") + " (" + tracks.size() + ")",
                        text("В плейлист «Импортированные»", "To the imported music"), () -> {
                            for (Row row : new java.util.ArrayList<>(rows.values())) startDownload(row);
                        }));
                addTracks(tracks);
            });
        }

        TextView header(String label) {
            TextView view = new TextView(context);
            view.setText(label);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setTextColor(textColor);
            view.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 4));
            return view;
        }

        TextView note(String label) {
            TextView view = new TextView(context);
            view.setText(label);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            view.setTextColor(withAlpha(textColor, 0xB0));
            view.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
            return view;
        }

        /** A row that opens something: a title and an optional grey line under it. */
        LinearLayout linkRow(String title, String subtitle, Runnable onClick) {
            LinearLayout view = new LinearLayout(context);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setMinimumHeight(dp(context, 56));
            view.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
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

        void refreshRows() {
            for (Row row : rows.values()) row.refresh();
        }
    }

    /** One result: tap to listen, the button on the right downloads. */
    private static final class Row {
        final Screen screen;
        final OtherSource.Track track;
        final LinearLayout view;
        final TextView title;
        final TextView subtitle;
        final ImageView download;
        final TextView percent;
        final ProgressBar busy;

        Row(Screen screen, OtherSource.Track track) {
            this.screen = screen;
            this.track = track;
            Context context = screen.context;

            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setMinimumHeight(dp(context, 64));
            view.setPadding(dp(context, 16), dp(context, 8), dp(context, 4), dp(context, 8));
            TypedValue ripple = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            view.setBackgroundResource(ripple.resourceId);
            view.setOnClickListener(v -> togglePreview(this));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(context);
            title.setText(track.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            subtitle.setTextColor(withAlpha(screen.textColor, 0xA0));
            texts.addView(title);
            texts.addView(subtitle);
            view.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            FrameLayout action = new FrameLayout(context);
            download = new ImageView(context);
            download.setScaleType(ImageView.ScaleType.CENTER);
            download.setImageTintList(ColorStateList.valueOf(screen.textColor));
            download.setContentDescription(text("Скачать", "Download"));
            download.setBackgroundResource(ripple.resourceId);
            download.setOnClickListener(v -> startDownload(this));
            action.addView(download, new FrameLayout.LayoutParams(dp(context, 48), dp(context, 48), Gravity.CENTER));
            busy = new ProgressBar(context);
            busy.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
            action.addView(busy, new FrameLayout.LayoutParams(dp(context, 28), dp(context, 28), Gravity.CENTER));
            percent = new TextView(context);
            percent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            percent.setTextColor(screen.textColor);
            percent.setGravity(Gravity.CENTER);
            action.addView(percent, new FrameLayout.LayoutParams(dp(context, 48), dp(context, 48), Gravity.CENTER));
            view.addView(action, new LinearLayout.LayoutParams(dp(context, 56), dp(context, 56)));

            refresh();
        }

        void refresh() {
            boolean playing = track.url.equals(playingUrl);
            title.setTextColor(playing ? ACCENT : screen.textColor);
            String duration = track.durationSeconds > 0
                    ? String.format(Locale.ROOT, "%d:%02d", track.durationSeconds / 60, track.durationSeconds % 60) : "";
            String state = playing ? (preparing ? text("загрузка…", "loading…") : text("играет", "playing")) : "";
            StringBuilder line = new StringBuilder(track.artist);
            for (String part : new String[]{duration, state}) {
                if (part.isEmpty()) continue;
                if (line.length() > 0) line.append(" · ");
                line.append(part);
            }
            subtitle.setText(line);

            Integer progress = downloading.get(track.url);
            boolean done = downloaded.contains(track.url);
            download.setVisibility(progress == null ? View.VISIBLE : View.INVISIBLE);
            download.setEnabled(!done);
            download.setImageResource(Utils.getResourceIdentifier(ResourceType.DRAWABLE,
                    done ? "ic_actions_downloaded" : "ic_actions_download_initial"));
            download.setImageTintList(done ? null : ColorStateList.valueOf(screen.textColor));
            busy.setVisibility(progress == null ? View.GONE : View.VISIBLE);
            percent.setVisibility(progress == null || progress <= 0 ? View.GONE : View.VISIBLE);
            percent.setText(progress == null ? "" : progress + "%");
        }
    }

    private static void refreshScreen() {
        Screen current = screen.get();
        if (current != null) current.refreshRows();
    }

    private static void togglePreview(Row row) {
        if (row.track.url.equals(playingUrl)) {
            stopPreview();
            return;
        }
        stopPreview();
        Context context = row.screen.context.getApplicationContext();
        String url = row.track.url;
        playingUrl = url;
        preparing = true;
        refreshScreen();
        network.execute(() -> {
            try {
                AudioStream stream = OtherSource.bestAudio(url);
                OtherSource.PartSource source = new OtherSource.PartSource(stream.getContent());
                handler.post(() -> {
                    if (!url.equals(playingUrl)) return;
                    try {
                        MediaPlayer created = new MediaPlayer();
                        created.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build());
                        created.setDataSource(source);
                        created.setOnPreparedListener(prepared -> {
                            if (player != prepared) return;
                            preparing = false;
                            // SoundCloud pauses its own playback when it loses the audio focus.
                            requestFocus(context);
                            prepared.start();
                            refreshScreen();
                        });
                        created.setOnCompletionListener(finished -> stopPreview());
                        created.setOnErrorListener((failed, what, extra) -> {
                            Logger.printInfo(() -> "Preview error " + what + "/" + extra);
                            stopPreview();
                            toast(context, text("Не получилось включить трек.", "Could not play the track."));
                            return true;
                        });
                        player = created;
                        created.prepareAsync();
                    } catch (Exception ex) {
                        Logger.printException(() -> "Could not start the preview", ex);
                        stopPreview();
                    }
                });
            } catch (Exception ex) {
                Logger.printException(() -> "Could not get the stream", ex);
                handler.post(() -> {
                    if (!url.equals(playingUrl)) return;
                    stopPreview();
                    if (needsSignIn(ex)) {
                        offerSignIn();
                        return;
                    }
                    toast(context, isRegionBlock(ex) ? REGION_BLOCKED_TEXT
                            : text("Не получилось включить трек.", "Could not play the track."));
                });
            }
        });
    }

    private static void stopPreview() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        Activity current = activity.get();
        if (focusRequest != null && current != null) {
            current.getSystemService(AudioManager.class).abandonAudioFocusRequest(focusRequest);
        }
        focusRequest = null;
        if (playingUrl == null) return;
        playingUrl = null;
        preparing = false;
        refreshScreen();
    }

    private static void requestFocus(Context context) {
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS) stopPreview();
                })
                .build();
        context.getSystemService(AudioManager.class).requestAudioFocus(focusRequest);
    }

    private static void startDownload(Row row) {
        String url = row.track.url;
        if (downloading.containsKey(url) || downloaded.contains(url)) return;
        Context context = row.screen.context.getApplicationContext();
        OtherSource.Track track = row.track;
        downloading.put(url, 0);
        refreshScreen();
        network.execute(() -> {
            File file = null;
            try {
                for (int attempt = 1; ; attempt++) {
                    AudioStream stream = OtherSource.bestAudio(url);
                    String name = (track.artist.isEmpty() ? "" : track.artist + " - ") + track.title
                            + "." + OtherSource.extensionOf(stream);
                    file = LocalMusic.newImportFile(context, name);
                    int[] shown = {-1};
                    try (OutputStream output = new FileOutputStream(file)) {
                        OtherSource.download(stream.getContent(), output, value -> {
                            if (value == shown[0]) return;
                            shown[0] = value;
                            handler.post(() -> {
                                if (downloading.containsKey(url)) downloading.put(url, value);
                                refreshScreen();
                            });
                        });
                        break;
                    } catch (OtherSource.RefusedException ex) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                        if (attempt >= 3) throw ex;
                    }
                }
                LocalMusic.onFileAdded();
                handler.post(() -> {
                    downloading.remove(url);
                    downloaded.add(url);
                    refreshScreen();
                    toast(context, text("Скачано: «" + track.title + "» — в плейлисте «Импортированные»",
                            "Downloaded \"" + track.title + "\" to the imported music"));
                });
            } catch (Exception ex) {
                Logger.printException(() -> "Could not download " + url, ex);
                if (file != null) //noinspection ResultOfMethodCallIgnored
                    file.delete();
                handler.post(() -> {
                    downloading.remove(url);
                    refreshScreen();
                    if (needsSignIn(ex)) {
                        offerSignIn();
                        return;
                    }
                    toast(context, isRegionBlock(ex) ? REGION_BLOCKED_TEXT : refused(ex)
                            ? text("YouTube не отдал файл: похоже, VPN шлёт запросы с разных адресов.",
                            "YouTube refused the file: the VPN seems to use different addresses.")
                            : text("Не получилось скачать «" + track.title + "».",
                            "Could not download \"" + track.title + "\"."));
                });
            }
        });
    }

    private static final String REGION_BLOCKED_TEXT = text("Российский IP — поиск Arsound отключён.",
            "Russian IP: the Arsound search is off.");

    private static <T> List<T> resultOrEmpty(java.util.concurrent.Future<List<T>> future) {
        try {
            return future.get();
        } catch (Exception ex) {
            Logger.printException(() -> "Arsound album or artist search failed", ex);
            return new java.util.ArrayList<>();
        }
    }

    private static boolean isRegionBlock(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof app.revanced.extension.soundcloud.network.RegionGuard.BlockedException) return true;
        }
        return false;
    }

    private static boolean needsSignIn(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof YouTubeAccount.SignInRequiredException) return true;
        }
        return false;
    }

    private static boolean signInOffered;

    /** An age-restricted track without an account: offers to sign in, once at a time. */
    private static void offerSignIn() {
        Activity current = activity.get();
        if (current == null || current.isFinishing() || signInOffered) return;
        signInOffered = true;
        try {
            new AlertDialog.Builder(current)
                    .setView(WelcomePermissions.createDialogContent(current,
                            text("У трека возрастное ограничение: YouTube отдаёт его только после входа в аккаунт.",
                                    "The track is age-restricted: YouTube gives it only to signed-in listeners."),
                            text("Войти в YouTube Music? Это по желанию: пароль вводится на странице Google, "
                                            + "а выйти можно в Настройках → Arsound → Аккаунт.",
                                    "Sign in to YouTube Music? It is optional: the password is typed into Google's page, "
                                            + "and you can sign out in Settings → Arsound → Account.")))
                    .setPositiveButton(text("Войти", "Sign in"), (dialog, which) -> YouTubeLoginActivity.start(current))
                    .setNegativeButton(text("Не сейчас", "Not now"), null)
                    .setOnDismissListener(dialog -> signInOffered = false)
                    .show();
        } catch (Exception ex) {
            signInOffered = false;
            Logger.printException(() -> "Could not offer the sign-in", ex);
        }
    }

    private static boolean refused(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof OtherSource.RefusedException) return true;
        }
        return false;
    }

    private static void showHelp(Context context) {
        try {
            new AlertDialog.Builder(context)
                    .setView(WelcomePermissions.createDialogContent(context,
                            text("Поиск Arsound нужен, чтобы скачивать треки, которые в SoundCloud скачать нельзя: "
                                            + "их там нет, они только для подписчиков, это отрывок или трек защищён.",
                                    "Arsound search is for downloading tracks that SoundCloud does not let you download: "
                                            + "missing ones, subscriber-only ones, previews or protected tracks."),
                            text("Треки ищутся в YouTube Music. Нажмите на трек, чтобы послушать и проверить, тот ли это; "
                                            + "нажмите ещё раз, чтобы остановить. Кнопка справа скачивает трек.",
                                    "Tracks are searched on YouTube Music. Tap a track to listen and check it is the right one; "
                                            + "tap again to stop. The button on the right downloads it."),
                            text("Скачанное появляется в плейлисте «Импортированные» — оттуда трек можно добавить в любой плейлист.",
                                    "Downloads appear in the \"Imported\" playlist, and can be added to any playlist from there.")))
                    .setPositiveButton(text("Понятно", "Got it"), null)
                    .show();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the search help", ex);
        }
    }

    private static void toast(Context context, String message) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int inverse(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return luminance > 0.5 ? 0xFF121212 : Color.WHITE;
    }
}
