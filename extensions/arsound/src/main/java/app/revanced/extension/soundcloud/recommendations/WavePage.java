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
import app.revanced.extension.soundcloud.player.TrackTitleResolver;
import app.revanced.extension.soundcloud.settings.WaveScreenLauncher;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The page of the "My Wave" tab ({@code navbar.WaveNavigationTarget}): the big moving picture with the play button in
 * the middle ({@link WaveOrb}), what the wave is doing, the sources as chips and the tracks it played, with their covers.
 * The finer settings (what to forget) are behind the gear, on the wave's settings screen.
 * <p>
 * The bottom bar makes the page once and hides it while another tab is open; the picture moves and the page looks at
 * the wave again every two seconds only while it is on screen. Main thread.
 */
public final class WavePage extends Fragment {
    /** How many tracks the list shows, and how many of them played before the one playing now. */
    private static final int RECENT = 10;
    private static final int BEFORE = 2;
    private static final long TICK_MS = 2000;

    private WaveOrb orb;
    private TextView action;
    private TextView status;
    private LinearLayout chips;
    private String chipStates = "";
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
        history.setPadding(0, Dimens.dp(context, 4), 0, Dimens.dp(context, 4));
        history.setBackground(rounded(ArsoundTheme.palette(context, "highlight"), Dimens.dp(context, 16)));
        column.addView(history, matchWidth());

        TextView note = label(context, "Body.Secondary", text(
                "Волна учится на ваших лайках и треках, дослушанных хотя бы 45 секунд. Пропущенные в первые 15 секунд "
                        + "и «Не нравится» больше не предлагаются.",
                "The wave learns from your likes and the tracks heard at least 45 seconds. Tracks skipped in the first "
                        + "15 seconds and \"Not for me\" are never offered again."));
        LinearLayout.LayoutParams noteParams = matchWidth();
        noteParams.topMargin = Dimens.dp(context, 12);
        column.addView(note, noteParams);

        update();

        // A soft veil under the status bar, so the page scrolling up does not run into the clock and the icons.
        FrameLayout root = new FrameLayout(context);
        root.addView(scroll);
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
        int plain = ArsoundTheme.palette(context, "highlight");
        int textColor = ArsoundTheme.palette(context, "primary");
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

    private void toggle(Context context) {
        if (MyWave.isActive()) MyWave.stop(context);
        else MyWave.start(context);
        update();
    }

    /** The button, the line under it and, when the wave added tracks, the history. */
    private void update() {
        if (orb == null) return;
        boolean on = MyWave.isActive();
        int added = MyWave.addedCount();
        orb.setPlaying(on);
        orb.setContentDescription(on ? text("Остановить Мою волну", "Stop My Wave") : text("Включить Мою волну", "Play My Wave"));
        action.setText(on ? text("Волна играет", "The wave is playing") : text("Слушать волну", "Play the wave"));
        status.setText(on
                ? added > 0 ? text("По вашему вкусу, без конца · треков в волне: " + added,
                        "To your taste, without end · tracks in the wave: " + added)
                : text("Подбираю треки…", "Picking the tracks…")
                : text("Музыка по вашему вкусу без конца. Нажмите, чтобы включить.",
                        "Music to your taste without end. Tap to play."));
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
            Map<String, String[]> tracks = findTracks(urns);
            Utils.runOnMainThread(() -> showHistory(urns, tracks, playing));
        });
    }

    /**
     * Titles, uploaders and covers of the tracks, off the main thread: those found before (kept on the phone), then
     * SoundCloud's own database, then its API. SoundCloud stores a track only once it has loaded it itself (to play it
     * or to show it in the queue), so the wave's next tracks are not in the database yet; the API knows them.
     */
    private static Map<String, String[]> findTracks(List<String> urns) {
        Map<String, String[]> tracks = WaveSession.tracks();
        tracks.keySet().retainAll(urns);
        List<String> missing = new java.util.ArrayList<>(urns);
        missing.removeAll(tracks.keySet());
        Map<String, String[]> found = new java.util.HashMap<>();
        if (!missing.isEmpty()) {
            found.putAll(TrackTitleResolver.storedTitles(missing));
            missing.removeAll(found.keySet());
        }
        if (!missing.isEmpty()) found.putAll(TrackTitleResolver.fetchTracks(missing));
        if (!found.isEmpty()) {
            tracks.putAll(found);
            WaveSession.putTracks(found, WaveSession.queue());
        }
        return tracks;
    }

    private void showHistory(List<String> urns, Map<String, String[]> tracks, String playing) {
        if (history == null) return;
        Context context = history.getContext();
        history.removeAllViews();
        if (urns.isEmpty()) {
            TextView none = label(context, "Body.Secondary", text("Здесь появятся треки волны: что играло, что играет и что дальше.",
                    "The wave's tracks will show here: what played, what plays and what comes next."));
            int pad = Dimens.dp(context, 16);
            none.setPadding(pad, pad, pad, pad);
            history.addView(none);
            return;
        }
        for (String urn : urns) history.addView(historyRow(context, tracks.get(urn), urn.equals(playing)));
        // A track neither SoundCloud nor its API gave (no connection, say) is looked for again a little later.
        if (!tracks.keySet().containsAll(urns) && retriesLeft-- > 0) retryIn = RETRY_TICKS;
    }

    /** A row of the list: the cover, the title and the uploader; the track playing now has its title in the accent. */
    private View historyRow(Context context, String[] track, boolean playing) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Dimens.dp(context, 12);
        row.setPadding(pad, Dimens.dp(context, 8), pad, Dimens.dp(context, 8));

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
