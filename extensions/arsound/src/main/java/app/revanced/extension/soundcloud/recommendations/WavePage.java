package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.util.TypedValue;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import java.util.List;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.player.CurrentPlayState;
import app.revanced.extension.soundcloud.player.MediaKeys;
import app.revanced.extension.soundcloud.player.TrackTitleResolver;
import app.revanced.extension.soundcloud.settings.WaveScreenLauncher;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.wallpaper.TopBarStyle;
import app.revanced.extension.soundcloud.wallpaper.WallpaperStore;

/**
 * The page of the "My Wave" tab ({@code navbar.WaveNavigationTarget}): the big moving picture with the play button in
 * the middle ({@link WaveOrb}, the player's play and pause for the wave), what the wave is doing, the sources as chips and the tracks it played, with their covers.
 * The finer settings (what to forget) are behind the gear, on the wave's settings screen.
 * <p>
 * The bottom bar makes the page once and hides it while another tab is open; the picture moves and the page looks at
 * the wave again every two seconds only while it is on screen. Main thread.
 */
public final class WavePage extends Fragment {
    /** How many tracks the list shows, and how many of them played before the one playing now. */
    private static final int RECENT = 15;
    private static final int BEFORE = 2;
    /** The line about how the wave works was hidden with "Do not show again". */
    private static final String NOTE_HIDDEN = "wave_note_hidden";
    private static final long TICK_MS = 2000;

    private WaveOrb orb;
    private TextView action;
    private TextView status;
    private LinearLayout chips;
    private String chipStates = "";
    /** What the last tap asked of the player, shown until {@link #expectedUntil}; null when nothing was asked. */
    private Boolean expected;
    private long expectedUntil;
    private LinearLayout history;
    private TextView historyHeading;
    private boolean resumed;
    /** What the list was last drawn for (the tracks added and the one playing), so it is read again only on a change. */
    private String shownKey = "";
    /** Ticks until the list is read again because some of its tracks were not in SoundCloud's database yet. */
    private int retryIn = -1;
    /** How many times more the list may be read again for the same tracks. */
    private int retriesLeft;
    private static final int RETRY_TICKS = 3;
    private static final int RETRIES = 3;
    private final Runnable tick = this::tick;

    public WavePage() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Context context = inflater.getContext();
        try {
            return build(context);
        } catch (Exception ex) {
            Logger.printException(() -> "My Wave: the tab page was not built", ex);
            return new FrameLayout(context);
        }
    }

    private View build(Context context) {
        int accent = ArsoundTheme.palette(context, "special");
        int side = Dimens.dp(context, 16);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        // Under the status bar at the top; at the bottom, room for the mini player and the bottom bar.
        column.setPadding(side, statusBarHeight(context) + Dimens.dp(context, 12), side, Dimens.dp(context, 170));
        scroll.addView(column, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(header(context));

        orb = new WaveOrb(context, accent, ArsoundTheme.palette(context, "primary"), ArsoundTheme.palette(context, "surface"));
        orb.setOnClickListener(v -> toggle(context));
        LinearLayout.LayoutParams orbParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        orbParams.topMargin = Dimens.dp(context, 8);
        column.addView(orb, orbParams);

        action = label(context, "H3.Primary", "");
        action.setGravity(Gravity.CENTER);
        column.addView(action, matchWidth());
        status = label(context, "Body.Secondary", "");
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = matchWidth();
        statusParams.topMargin = Dimens.dp(context, 4);
        column.addView(status, statusParams);

        column.addView(heading(context, text("Откуда брать треки", "Where the tracks come from")));
        chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(chips, matchWidth());
        fillChips(context, accent);

        historyHeading = heading(context, text("В волне", "In the wave"));
        column.addView(historyHeading);
        history = new LinearLayout(context);
        history.setOrientation(LinearLayout.VERTICAL);
        column.addView(history, matchWidth());

        if (!Settings.getBoolean(NOTE_HIDDEN, false)) {
            View note = note(context);
            LinearLayout.LayoutParams noteParams = matchWidth();
            noteParams.topMargin = Dimens.dp(context, 12);
            column.addView(note, noteParams);
        }

        update();

        // A soft veil under the status bar, so the page scrolling up does not run into the clock and the icons. Over a
        // wallpaper it follows the top bar style of Appearance: with "No band" nothing covers the picture.
        FrameLayout root = new FrameLayout(context);
        root.addView(scroll);
        if (WallpaperStore.isActive() && TopBarStyle.current() == TopBarStyle.CLEAR) return root;
        View veil = new View(context);
        int surface = ArsoundTheme.palette(context, "surface");
        veil.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Colors.withAlpha(surface, 0xE6), Colors.withAlpha(surface, 0x00)}));
        root.addView(veil, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                statusBarHeight(context) + Dimens.dp(context, 20)));
        return root;
    }

    /** The title of the tab and the gear that opens the wave's settings. */
    private View header(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(context, "H1.Primary", text("Моя волна", "My Wave"));
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView gear = new ImageView(context);
        int icon = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "ic_actions_cog");
        if (icon != 0) gear.setImageResource(icon);
        gear.setImageTintList(ColorStateList.valueOf(ArsoundTheme.palette(context, "primary")));
        int pad = Dimens.dp(context, 8);
        gear.setPadding(pad, pad, pad, pad);
        gear.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                rounded(0xFFFFFFFF, Dimens.dp(context, 100))));
        gear.setContentDescription(text("Настройки волны", "Wave settings"));
        gear.setOnClickListener(v -> context.startActivity(WaveScreenLauncher.intent(context)));
        int size = Dimens.dp(context, 44);
        row.addView(gear, new LinearLayout.LayoutParams(size, size));
        return row;
    }

    /** One chip per source, flooded with the accent when it is on. At least one stays on. */
    private void fillChips(Context context, int accent) {
        chips.removeAllViews();
        int textColor = ArsoundTheme.palette(context, "primary");
        // An off chip is a thin outline with a faint tint of the text colour: no plate over the wallpaper.
        int plain = Colors.withAlpha(textColor, 0x14);
        chipStates = "";
        for (WaveSource source : WaveSource.values()) {
            chipStates += source.isOn() ? '1' : '0';
            WaveChip chip = new WaveChip(context, source.shortLabel(), source.isOn(), accent, plain, textColor);
            chip.setToggle(nowOn -> {
                if (!nowOn && WaveSource.onCount() <= 1) {
                    Toast.makeText(context, text("Хотя бы один источник остаётся включённым",
                            "At least one source stays on"), Toast.LENGTH_SHORT).show();
                    return false;
                }
                source.set(nowOn);
                chipStates = currentStates();
                return true;
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = Dimens.dp(context, 8);
            chips.addView(chip, params);
        }
    }

    /** The sources on and off, one character each, to see they were changed elsewhere. */
    private static String currentStates() {
        StringBuilder states = new StringBuilder();
        for (WaveSource source : WaveSource.values()) states.append(source.isOn() ? '1' : '0');
        return states.toString();
    }

    /**
     * The button is the player's: it starts the wave when none plays, and pauses or resumes the music of a wave that
     * plays, as the player's own button does.
     */
    private void toggle(Context context) {
        if (!MyWave.isActive()) {
            MyWave.start(context);
            expected = Boolean.TRUE;
        } else if (CurrentPlayState.isPlaying()) {
            MediaKeys.pause(context);
            expected = Boolean.FALSE;
        } else {
            MediaKeys.play(context);
            expected = Boolean.TRUE;
        }
        // The player answers in a moment: until then the button shows what was asked, so it does not flicker back.
        expectedUntil = android.os.SystemClock.uptimeMillis() + 1500;
        update();
        orb.postDelayed(this::update, 600);
        orb.postDelayed(this::update, 1600);
    }

    /** Whether the music plays, as the button shows it: what was just asked for, else what the player says. */
    private boolean playing() {
        if (expected != null && android.os.SystemClock.uptimeMillis() < expectedUntil) return expected;
        expected = null;
        return MyWave.isActive() && CurrentPlayState.isPlaying();
    }

    /** The button, the line under it and, when the wave added tracks, the history. */
    private void update() {
        if (orb == null) return;
        boolean on = MyWave.isActive();
        boolean sounds = playing();
        int added = MyWave.addedCount();
        orb.setPlaying(sounds);
        orb.setContentDescription(sounds ? text("Пауза", "Pause") : text("Играть", "Play"));
        action.setText(!on ? text("Слушать волну", "Play the wave")
                : sounds ? text("Волна играет", "The wave is playing") : text("Волна на паузе", "The wave is paused"));
        String problem = MyWave.problem();
        status.setText(on
                ? added > 0 ? text("По вашему вкусу, без конца · треков в волне: " + added,
                        "To your taste, without end · tracks in the wave: " + added)
                : text("Подбираю треки…", "Picking the tracks…")
                : problem != null ? problem
                : text("Музыка по вашему вкусу без конца", "Music to your taste without end"));
        if (!on) MyWave.prepare();
        historyHeading.setText(MyWave.isPast() ? text("В прошлый раз", "Last time") : text("В волне", "In the wave"));
        String key = added + "/" + MyWave.current() + "/" + on;
        if (!key.equals(shownKey)) retriesLeft = RETRIES;
        if (!key.equals(shownKey) || retryIn == 0) {
            shownKey = key;
            retryIn = -1;
            loadHistory();
        } else if (retryIn > 0) {
            retryIn--;
        }
    }

    private void loadHistory() {
        List<String> urns = MyWave.around(BEFORE, RECENT);
        String playing = MyWave.current();
        Utils.runOnBackgroundThread(() -> {
            // What the phone knows is shown at once; the API, which may be slow or out of reach, fills in the rest.
            Map<String, String[]> known = knownTracks(urns);
            Utils.runOnMainThread(() -> showHistory(urns, known, playing));
            if (WaveSession.tracksCover(urns)) return;
            Map<String, String[]> fetched = fetchMissing(urns, known);
            if (!fetched.isEmpty()) Utils.runOnMainThread(() -> showHistory(urns, fetched, playing));
        });
    }

    /**
     * Titles, artists and covers the phone has, off the main thread: those found before (kept on the phone), then
     * SoundCloud's own database, then the names kept by the first version of the list (uploaders, not artists).
     */
    private static Map<String, String[]> knownTracks(List<String> urns) {
        Map<String, String[]> tracks = WaveSession.tracks();
        tracks.keySet().retainAll(urns);
        List<String> missing = new java.util.ArrayList<>(urns);
        missing.removeAll(tracks.keySet());
        if (!missing.isEmpty()) {
            Map<String, String[]> stored = TrackTitleResolver.storedTitles(missing);
            tracks.putAll(stored);
            WaveSession.putTracks(stored, WaveSession.queue());
            missing.removeAll(stored.keySet());
        }
        if (!missing.isEmpty()) {
            Map<String, String[]> old = WaveSession.oldTracks();
            for (String urn : missing) if (old.containsKey(urn)) tracks.put(urn, old.get(urn));
        }
        return tracks;
    }

    /**
     * The tracks the phone does not know, asked from SoundCloud's API: it knows the wave's next tracks, which SoundCloud
     * stores only once it has loaded them itself. All the tracks, or empty when the API gave nothing new.
     */
    private static Map<String, String[]> fetchMissing(List<String> urns, Map<String, String[]> known) {
        List<String> missing = new java.util.ArrayList<>(urns);
        missing.removeAll(WaveSession.tracks().keySet());
        Map<String, String[]> found = TrackTitleResolver.fetchTracks(missing);
        if (found.isEmpty()) return found;
        WaveSession.putTracks(found, WaveSession.queue());
        Map<String, String[]> all = new java.util.HashMap<>(known);
        all.putAll(found);
        return all;
    }

    private void showHistory(List<String> urns, Map<String, String[]> tracks, String playing) {
        if (history == null) return;
        Context context = history.getContext();
        history.removeAllViews();
        if (urns.isEmpty()) {
            TextView none = label(context, "Body.Secondary", text("Здесь появятся треки волны: что играло, что играет и что дальше.",
                    "The wave's tracks will show here: what played, what plays and what comes next."));
            none.setPadding(0, Dimens.dp(context, 4), 0, Dimens.dp(context, 4));
            history.addView(none);
            return;
        }
        for (String urn : urns) {
            View row = historyRow(context, tracks.get(urn), urn.equals(playing));
            // A tap plays the wave from this track, the ones after it following, as in a playlist.
            row.setOnClickListener(v -> {
                MyWave.playFrom(urn);
                expected = Boolean.TRUE;
                expectedUntil = android.os.SystemClock.uptimeMillis() + 1500;
                update();
            });
            history.addView(row);
        }
        // A track neither SoundCloud nor its API gave (no connection, say) is looked for again a little later.
        if (!tracks.keySet().containsAll(urns) && retriesLeft-- > 0) retryIn = RETRY_TICKS;
    }

    /** A row of the list: the cover, the title and the artist; the track playing now has its title in the accent. */
    private View historyRow(Context context, String[] track, boolean playing) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // Only a ripple under the finger: no plate over the wallpaper.
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null,
                rounded(0xFFFFFFFF, Dimens.dp(context, 10))));
        row.setClickable(true);
        // The rows lie straight on the page, like SoundCloud's track lists, with no card under them.
        row.setPadding(0, Dimens.dp(context, 8), 0, Dimens.dp(context, 8));

        ImageView cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(rounded(Colors.withAlpha(ArsoundTheme.palette(context, "special"), 0x40), Dimens.dp(context, 8)));
        float corner = Dimens.dpExact(context, 8);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
            }
        });
        cover.setClipToOutline(true);
        WaveCovers.show(cover, track == null || track.length < 3 ? null : WaveCovers.address(track[2]));
        int size = Dimens.dp(context, 48);
        row.addView(cover, new LinearLayout.LayoutParams(size, size));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = label(context, "Body.Primary", track == null ? text("Трек SoundCloud", "SoundCloud track") : track[0]);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        if (playing) {
            title.setTextColor(ArsoundTheme.palette(context, "special"));
            title.setTypeface(Typeface.DEFAULT_BOLD);
        }
        texts.addView(title);
        String uploader = track == null || track[1] == null ? "" : track[1];
        if (!uploader.isEmpty()) {
            TextView artist = label(context, "Body.Secondary", uploader);
            artist.setSingleLine(true);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(artist);
        }
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = Dimens.dp(context, 12);
        row.addView(texts, textParams);
        return row;
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        onShown();
    }

    @Override
    public void onPause() {
        resumed = false;
        onShown();
        super.onPause();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        onShown();
    }

    @Override
    public void onDestroyView() {
        if (orb != null) {
            orb.setRunning(false);
            orb.removeCallbacks(tick);
        }
        orb = null;
        history = null;
        super.onDestroyView();
    }

    /** Starts or stops the movement and the looking, as the page comes on screen or leaves it. */
    private void onShown() {
        if (orb == null) return;
        boolean visible = resumed && !isHidden();
        orb.setRunning(visible);
        orb.removeCallbacks(tick);
        if (visible) {
            // The sources may have been changed on the settings screen behind the gear.
            if (!currentStates().equals(chipStates)) fillChips(orb.getContext(), ArsoundTheme.palette(orb.getContext(), "special"));
            update();
            orb.postDelayed(tick, TICK_MS);
        }
    }

    private void tick() {
        if (orb == null || !resumed || isHidden()) return;
        update();
        orb.postDelayed(tick, TICK_MS);
    }

    /** The short line at the end of the page: a tap opens how the wave works, where it can be hidden for good. */
    private View note(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Dimens.dp(context, 8), 0, Dimens.dp(context, 8));
        TextView line = label(context, "Body.Secondary", text("Как волна подбирает треки", "How the wave picks tracks"));
        row.addView(line, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView mark = label(context, "Body.Secondary", "?");
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setStroke(Dimens.dp(context, 1), mark.getCurrentTextColor());
        mark.setBackground(circle);
        int size = Dimens.dp(context, 22);
        row.addView(mark, new LinearLayout.LayoutParams(size, size));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null,
                rounded(0xFFFFFFFF, Dimens.dp(context, 10))));
        row.setContentDescription(text("Как волна подбирает треки, подробнее", "How the wave picks tracks, more"));
        row.setOnClickListener(v -> explain(context, row));
        return row;
    }

    /** How the wave works, with "Do not show again", which takes the line off the page. */
    private void explain(Context context, View note) {
        int accent = ArsoundTheme.palette(context, "special");
        int primary = ArsoundTheme.palette(context, "primary");
        int secondary = ArsoundTheme.palette(context, "secondary");
        int count = MyWave.addedCount();
        LinearLayout content = new LinearLayout(context);
        content.setTag(ArsoundDialog.KEEP_COLORS);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Dimens.dp(context, 24), 0, Dimens.dp(context, 24), Dimens.dp(context, 4));
        content.addView(paragraph(context, primary, text(
                "Волна подбирает треки по вашим лайкам и трекам, дослушанным хотя бы 45 секунд.",
                "The wave picks tracks by your likes and the tracks heard at least 45 seconds.")));
        content.addView(paragraph(context, secondary, text(
                "Она наполняется постепенно, пока вы слушаете: сначала несколько треков, потом следующие — когда впереди "
                        + "остаётся шесть, волна сама добавляет новые. Поэтому сейчас в ней " + count + " "
                        + tracksWord(count) + ", дальше будет больше: она не кончается.",
                "It fills up gradually as you listen: a few tracks first, then more — when six are left ahead, the wave "
                        + "adds new ones by itself. So now it holds " + count + " tracks, and there will be more: it does "
                        + "not end.")));
        content.addView(paragraph(context, secondary, text(
                "«Не нравится» убирает трек навсегда. Трек, пропущенный в первые 15 секунд, не считается неудачным: "
                        + "он отдохнёт три дня и потом может вернуться.",
                "\"Not for me\" removes a track for good. A track skipped in the first 15 seconds does not count as a "
                        + "miss: it rests three days and may come back after that.")));
        content.addView(paragraph(context, secondary, text(
                "Нажмите на трек в списке — волна заиграет с него.",
                "Tap a track in the list and the wave plays from it.")));

        CheckBox box = new CheckBox(context);
        box.setText(text("Больше не показывать", "Do not show again"));
        box.setTextColor(secondary);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.setButtonTintList(ColorStateList.valueOf(accent));

        new ArsoundDialog(context)
                .setTitle(text("Как работает волна", "How the wave works"))
                .setView(content)
                .setButtonsStart(box)
                .setPositiveButton(text("Понятно", "Got it"), (dialog, which) -> {
                    if (!box.isChecked()) return;
                    Settings.putBoolean(NOTE_HIDDEN, true);
                    if (note.getParent() instanceof ViewGroup) ((ViewGroup) note.getParent()).removeView(note);
                })
                .show();
    }

    /** "трек", "трека" or "треков" for the number. */
    private static String tracksWord(int count) {
        int tens = count % 100, ones = count % 10;
        if (tens >= 11 && tens <= 14) return "треков";
        if (ones == 1) return "трек";
        if (ones >= 2 && ones <= 4) return "трека";
        return "треков";
    }

    private static TextView paragraph(Context context, int color, CharSequence value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setPadding(0, Dimens.dp(context, 6), 0, Dimens.dp(context, 6));
        return view;
    }

    private static TextView label(Context context, String style, String value) {
        TextView view = new TextView(context);
        int id = Utils.getResourceIdentifier(ResourceType.STYLE, style);
        if (id != 0) view.setTextAppearance(id);
        view.setText(value);
        return view;
    }

    private static TextView heading(Context context, String value) {
        TextView view = label(context, "H4.Primary", value);
        view.setPadding(0, Dimens.dp(context, 28), 0, Dimens.dp(context, 10));
        return view;
    }

    private static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private static int statusBarHeight(Context context) {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id == 0 ? Dimens.dp(context, 24) : context.getResources().getDimensionPixelSize(id);
    }
}
