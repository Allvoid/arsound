package app.revanced.extension.soundcloud.lyrics;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The lyrics of one player page.
 * <p>
 * The layer is added to the page's views between the cover and the title, so the title, the buttons, the waveform and
 * the big play buttons stay above it. While the lyrics are on, the cover behind is blurred (Android 12 and later;
 * older versions get a darker veil) and the words stand in the middle, between the title block and the waveform.
 * <p>
 * Pages are reused by the player for other tracks, so a layer lives as long as its page and is told by
 * {@link #bind} what the page shows now. It works only for the page of the playing track, only while the player is
 * open and the lyrics are on; otherwise it does nothing at all: no lookup, no frame callbacks.
 */
final class LyricsLayer extends FrameLayout implements LyricsListView.Host {
    /** What the page shows. */
    private enum Phase {
        /** Nothing: another track's page, or the lyrics are off. */
        IDLE,
        /** The words are being looked up. */
        LOADING,
        /** The words are on screen. */
        LIST,
        /** A message is on screen: no lyrics, no connection. */
        STATUS
    }

    /** Marks a page that cannot take a layer, so it is not tried again on every bind. */
    private static final Object UNSUPPORTED = new Object();

    /** The highlight comes this much before the player's own position: sound reaches the ear after the clock says. */
    private static final long LEAD_MS = 150;
    private static final long FADE_IN_MS = 320;
    private static final long FADE_OUT_MS = 220;
    /** A lookup shorter than this shows no placeholder: a stored text appears without a flash of "searching". */
    private static final long SHIMMER_DELAY_MS = 250;
    private static final float BLUR_DP = 22f;
    /** The words need room: a page whose free middle is shorter than this has no lyrics (a phone on its side). */
    private static final int MIN_HEIGHT_DP = 160;
    /**
     * A page that has lost its lyrics for lack of room needs this much more to get them back. The button itself makes
     * the title block taller, so without the margin a page right at the limit would flip between the two on every layout.
     */
    private static final int ROOM_MARGIN_DP = 60;

    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /** Ids of the views of a player page and of the button, found once. */
    private static final class Ids {
        static boolean loaded;
        static int page, artwork, overlay, header, holder, corner, waveform, title, toggle, iconOff, iconOn;

        static void load() {
            if (loaded) return;
            page = id("player_track_page");
            artwork = id("track_page_artwork");
            overlay = id("artwork_overlay_dark");
            header = id("track_page_header");
            holder = id("timestamp_holder");
            corner = id("track_page_top_left_corner");
            waveform = id("waveform_container");
            title = id("track_page_title");
            toggle = id("arsound_lyrics_toggle");
            iconOff = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_lyrics");
            iconOn = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_lyrics_on");
            loaded = true;
        }

        private static int id(String name) {
            return Utils.getResourceIdentifier(ResourceType.ID, name);
        }
    }

    private final ViewGroup page;
    private final View artwork;
    private final View overlay;
    private final View header;
    private final View holder;
    private final View title;
    private final ImageButton toggle;
    private final ColorStateList offTint;

    private final View scrim;
    private final FrameLayout content;
    private final LyricsListView list;
    private final TextView status;
    private final LyricsShimmer shimmer;

    private TrackInfo info;
    private boolean flagCurrent;
    /** Whether the page shows the track that plays. */
    private boolean current;
    private Phase phase = Phase.IDLE;
    private Lyrics lyrics;
    /** The signature of the track whose words are loaded or on the way; null when none is wanted. */
    private String loadedSignature;
    private View displayed;

    /** How much of the lyrics the button has switched on, 0 to 1; animated by taps on the button. */
    private float shown;
    private ValueAnimator showAnimator;
    /** How far the player is open, 0 to 1. */
    private float expand;
    private boolean fits = true;
    private float blur = -1;
    private float pauseDim = 1;
    private Boolean buttonOn;
    private boolean looping;
    private boolean predrawing;
    /** Whether the lyrics should be doing something now; worked out by {@link #refresh}, read by the frame loop. */
    private boolean active;
    /** The old words fade out until then; the new ones wait for it. */
    private long fadeOutUntil;
    private boolean lastEnabled = true;

    private final Runnable showShimmer = new Runnable() {
        @Override
        public void run() {
            if (phase == Phase.LOADING) show(shimmer);
        }
    };

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            looping = false;
            try {
                if (!shouldLoop()) return;
                tick(true);
                looping = true;
                Choreographer.getInstance().postFrameCallback(this);
            } catch (Throwable ex) {
                Logger.printException(() -> "Lyrics: a frame of the lyrics failed", ex);
            }
        }
    };

    /** The words dim a little while the music is paused, like the cover does under SoundCloud's own pause overlay. */
    private final ViewTreeObserver.OnPreDrawListener predraw = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            try {
                if (overlay != null) {
                    float dim = 1f - 0.5f * overlay.getAlpha();
                    if (Math.abs(dim - pauseDim) > 0.01f) {
                        pauseDim = dim;
                        content.setAlpha(dim);
                    }
                }
            } catch (Throwable ex) {
                Logger.printException(() -> "Lyrics: could not dim the words", ex);
            }
            return true;
        }
    };

    private final View.OnLayoutChangeListener pageLayout = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop,
                                   int oldRight, int oldBottom) {
            try {
                updateGeometry();
            } catch (Throwable ex) {
                Logger.printException(() -> "Lyrics: could not place the lyrics", ex);
            }
        }
    };

    // region Creating

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    /** The page of the player, whatever the view the player hands over wraps it in. */
    static View pageOf(View view) {
        Ids.load();
        return Ids.page == 0 ? null : view.findViewById(Ids.page);
    }

    /** The layer of a page if it has one; never makes one. */
    static LyricsLayer existing(View page) {
        Ids.load();
        Object tag = Ids.toggle == 0 ? null : page.getTag(Ids.toggle);
        return tag instanceof LyricsLayer ? (LyricsLayer) tag : null;
    }

    /** Takes the lyrics button off a page that has no layer: the lyrics are switched off. */
    static void hideButton(View page) {
        Ids.load();
        View button = Ids.toggle == 0 ? null : page.findViewById(Ids.toggle);
        if (button != null && button.getVisibility() != GONE) button.setVisibility(GONE);
    }

    /** The layer of a page, made on first use; null if the page has no room for one. */
    static LyricsLayer of(View page) {
        Ids.load();
        if (Ids.toggle == 0) return null;
        Object tag = page.getTag(Ids.toggle);
        if (tag instanceof LyricsLayer) return (LyricsLayer) tag;
        if (tag == UNSUPPORTED) return null;
        LyricsLayer layer = null;
        try {
            layer = create(page);
        } catch (Throwable ex) {
            Logger.printException(() -> "Lyrics: could not add the lyrics to the player page", ex);
        }
        if (layer == null) {
            // A page without the button or the views to stand on: no lyrics there, and no dead button either.
            View button = page.findViewById(Ids.toggle);
            if (button != null) button.setVisibility(GONE);
        }
        page.setTag(Ids.toggle, layer == null ? UNSUPPORTED : layer);
        return layer;
    }

    private static LyricsLayer create(View page) {
        if (!(page instanceof RelativeLayout)) return null;
        RelativeLayout parent = (RelativeLayout) page;
        View artwork = page.findViewById(Ids.artwork);
        View button = page.findViewById(Ids.toggle);
        View anchor = page.findViewById(Ids.corner);
        if (anchor == null) anchor = page.findViewById(Ids.waveform);
        if (artwork == null || !(button instanceof ImageButton) || anchor == null) return null;
        int index = parent.indexOfChild(anchor);
        if (index < 0 || artwork.getParent() != parent) return null;

        LyricsLayer layer = new LyricsLayer(parent, artwork, (ImageButton) button);
        // The layer takes the cover's place on the page: same bounds, one step above it.
        ViewGroup.LayoutParams cover = artwork.getLayoutParams();
        RelativeLayout.LayoutParams params = cover instanceof RelativeLayout.LayoutParams
                ? new RelativeLayout.LayoutParams((RelativeLayout.LayoutParams) cover)
                : new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        parent.addView(layer, index, params);
        return layer;
    }

    private LyricsLayer(ViewGroup page, View artwork, ImageButton toggle) {
        super(page.getContext());
        Context context = getContext();
        this.page = page;
        this.artwork = artwork;
        this.toggle = toggle;
        this.overlay = page.findViewById(Ids.overlay);
        this.header = page.findViewById(Ids.header);
        this.holder = page.findViewById(Ids.holder);
        this.title = page.findViewById(Ids.title);
        ColorStateList tint = toggle.getImageTintList();
        offTint = tint != null ? tint
                : ColorStateList.valueOf(ArsoundTheme.isPlayerSkinEnabled() ? 0xFFFFFFFF : 0xFF000000);

        setVisibility(GONE);
        // The cover stays under the layer, so the fade of the layer is all the lyrics need.
        scrim = new View(context);
        scrim.setBackground(scrimDrawable(context));
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        content = new FrameLayout(context);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        list = new LyricsListView(context, this);
        list.setVisibility(GONE);
        list.setContentDescription(text("Текст песни", "Lyrics"));
        content.addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        status = new TextView(context);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        status.setTextColor(0xCCFFFFFF);
        status.setShadowLayer(dp(8), 0, dp(1.5f), 0x99000000);
        status.setLineSpacing(dp(3), 1f);
        status.setPadding(Math.round(dp(32)), Math.round(dp(16)), Math.round(dp(32)), Math.round(dp(16)));
        status.setVisibility(GONE);
        content.addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        shimmer = new LyricsShimmer(context);
        shimmer.setVisibility(GONE);
        content.addView(shimmer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LyricsManager.toggle();
            }
        });
        page.addOnLayoutChangeListener(pageLayout);

        // The player opens by sliding: before the first slide is seen it is closed, or it opened without telling.
        expand = LyricsManager.expand();
        shown = LyricsManager.isVisible() ? 1f : 0f;
    }

    /** Dark, a little darker toward the bottom, in the theme's surface: what the words stand on. */
    private static GradientDrawable scrimDrawable(Context context) {
        int surface = LyricsManager.surface(context) & 0x00FFFFFF;
        // Without a blur (before Android 12) the cover is sharp, so the veil is heavier.
        int[] alphas = CoverBlur.isSupported() ? new int[]{0x66, 0x73, 0x8C} : new int[]{0xC0, 0xCC, 0xDC};
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                (alphas[0] << 24) | surface, (alphas[1] << 24) | surface, (alphas[2] << 24) | surface});
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    // endregion

    // region What the page shows

    /**
     * The page is filled with a track, or emptied (a null URN). Called by the player on every state of the track, a
     * few times a second, so it does nothing when nothing changed.
     */
    void bind(String urn, String title, String uploader, long durationMs, boolean flagCurrent) {
        boolean trackChanged;
        if (urn == null) {
            trackChanged = info != null;
            info = null;
        } else if (info != null && info.sameTrack(urn, title, uploader, durationMs)) {
            trackChanged = false;
        } else {
            info = new TrackInfo(urn, title, uploader, durationMs);
            trackChanged = true;
        }
        this.flagCurrent = flagCurrent;
        boolean wasCurrent = current;
        current = isCurrentTrack();
        boolean drifted = resync();
        if (trackChanged) {
            forgetWords();
            refresh();
        } else if (wasCurrent != current) {
            if (!current) leaveCurrent();
            refresh();
        } else if (drifted || LyricsManager.isEnabled() != lastEnabled) {
            refresh();
        } else {
            updateToggleButton();
        }
    }

    /**
     * Takes the state the page may have missed. The player keeps pages aside for later tracks, and they are told
     * neither when the player slides nor when the button is tapped; a page that comes back would otherwise act on how
     * things stood when it left.
     *
     * @return Whether the page is now open or shut, shown or hidden, differently from before.
     */
    private boolean resync() {
        boolean wasOpen = expand > 0.5f;
        float wasShown = shown;
        expand = LyricsManager.isExpandKnown() ? LyricsManager.expand() : guessExpand();
        // The fade of the button sets `shown` itself while it runs.
        if (showAnimator == null || !showAnimator.isRunning()) shown = LyricsManager.isVisible() ? 1f : 0f;
        return (expand > 0.5f) != wasOpen || shown != wasShown;
    }

    /** The player says which page plays; the media session says it too, and is right when they differ for a moment. */
    private boolean isCurrentTrack() {
        if (info == null) return false;
        String playing = LyricsClock.urn();
        return playing != null ? playing.equals(info.urn) : flagCurrent;
    }

    /** Before the player has slid once, its state is read from where the page is. */
    private float guessExpand() {
        if (page.getHeight() == 0 || !page.isShown()) return expand;
        int[] position = new int[2];
        page.getLocationInWindow(position);
        View root = page.getRootView();
        return position[1] < root.getHeight() * 0.35f ? 1f : 0f;
    }

    /** The player is open this far, 0 (the mini player) to 1 (full screen). */
    void onSlide(float fraction) {
        boolean wasOpen = expand > 0.5f;
        expand = fraction;
        LyricsManager.setExpand(fraction);
        applyVisuals();
        if ((fraction > 0.5f) != wasOpen) refresh();
    }

    /** The button was tapped, here or on another page. */
    void onToggleChanged() {
        Boolean before = buttonOn;
        updateToggleButton();
        if (before != null && toggle.getVisibility() == VISIBLE) {
            toggle.setScaleX(0.8f);
            toggle.setScaleY(0.8f);
            toggle.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new OvershootInterpolator(2.2f)).start();
        }
        animateShown(LyricsManager.isVisible() ? 1f : 0f);
        refresh();
    }

    /** The player's state changed: another track, play or pause, a jump. */
    void onClockChanged() {
        boolean wasCurrent = current;
        current = isCurrentTrack();
        if (wasCurrent != current) {
            if (!current) leaveCurrent();
            refresh();
            return;
        }
        tick(isVisibleNow());
        updateLoop();
    }

    // endregion

    // region Deciding what to do

    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** Whether this page can have lyrics at all: the setting, the screen's shape and the room between title and waveform. */
    private boolean available() {
        return LyricsManager.isEnabled() && !landscape() && fits;
    }

    /** Whether the lyrics should be doing something: on, open, on screen. */
    private boolean wanted() {
        return available() && LyricsManager.isVisible() && expand > 0.5f && isAttachedToWindow();
    }

    private boolean isVisibleNow() {
        return getVisibility() == VISIBLE && getAlpha() > 0.5f;
    }

    /** Brings everything in line with the state: the button, the veil and blur, the lookup, the frame loop. */
    private void refresh() {
        lastEnabled = LyricsManager.isEnabled();
        updateToggleButton();
        applyVisuals();
        active = wanted();
        if (active && info != null) {
            if (current) {
                ensureLoaded();
                // A page that waited for its turn with the words already on it goes to the line that sings now.
                if (phase == Phase.LIST) tick(false);
            } else {
                showStored();
            }
        } else if (phase == Phase.LOADING) {
            // Closed or switched off in the middle of a lookup: the answer is kept, the page starts over when wanted.
            forgetWords();
        }
        updateLoop();
    }

    private void ensureLoaded() {
        String signature = info.query().signature;
        if (signature.equals(loadedSignature)) return;
        loadedSignature = signature;
        lyrics = null;
        setPhase(Phase.LOADING);
        LyricsManager.load(info);
    }

    /**
     * The page of the track before or after the playing one shows the words if they are at hand in memory, so a swipe
     * to it already has them. It never asks for them: only the playing track is looked up.
     */
    private void showStored() {
        String signature = info.query().signature;
        if (signature.equals(loadedSignature)) return;
        LyricsResult known = LyricsRepository.peek(info);
        if (known == null || known.status != LyricsResult.Status.FOUND) return;
        loadedSignature = signature;
        applyWords(known.lyrics);
    }

    /** Drops the words of the track this page had, so the next track starts clean. */
    private void forgetWords() {
        loadedSignature = null;
        lyrics = null;
        removeCallbacks(showShimmer);
        phase = Phase.IDLE;
        show(null);
    }

    /** The page is not the playing one any more: its words stay for a swipe back, a lookup or a message does not. */
    private void leaveCurrent() {
        if (phase != Phase.LIST) forgetWords();
    }

    // endregion

    // region The button

    private void updateToggleButton() {
        boolean enabled = available();
        int visibility = enabled ? VISIBLE : GONE;
        if (toggle.getVisibility() != visibility) toggle.setVisibility(visibility);
        if (!enabled) return;
        boolean on = LyricsManager.isVisible();
        if (buttonOn != null && buttonOn == on) return;
        buttonOn = on;
        int icon = on ? Ids.iconOn : Ids.iconOff;
        if (icon != 0) toggle.setImageResource(icon);
        toggle.setImageTintList(on ? ColorStateList.valueOf(LyricsManager.accent(getContext())) : offTint);
        toggle.setSelected(on);
        toggle.setContentDescription(on ? text("Скрыть текст песни", "Hide lyrics")
                : text("Показать текст песни", "Show lyrics"));
    }

    // endregion

    // region Looks

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private void animateShown(float target) {
        if (showAnimator != null) showAnimator.cancel();
        showAnimator = null;
        if (!isAttachedToWindow() || !available() || expand <= 0.5f) {
            shown = target;
            applyVisuals();
            return;
        }
        showAnimator = ValueAnimator.ofFloat(shown, target);
        showAnimator.setDuration(target > shown ? FADE_IN_MS : FADE_OUT_MS);
        showAnimator.setInterpolator(EASE);
        showAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                try {
                    shown = (float) animation.getAnimatedValue();
                    applyVisuals();
                } catch (Throwable ex) {
                    Logger.printException(() -> "Lyrics: could not fade the lyrics", ex);
                }
            }
        });
        showAnimator.start();
    }

    /** Fades the veil and the words, blurs the cover and slides the words up, all by how far the lyrics are on. */
    private void applyVisuals() {
        float on = available() ? shown : 0f;
        float alpha = on * clamp((expand - 0.4f) / 0.5f);
        boolean visible = alpha > 0.01f;
        if (visible != (getVisibility() == VISIBLE)) setVisibility(visible ? VISIBLE : GONE);
        if (visible) {
            setAlpha(alpha);
            content.setTranslationY((1f - clamp((on - 0.3f) / 0.7f)) * dp(14));
        }
        float radius = visible ? alpha * dp(BLUR_DP) : 0f;
        if (Math.abs(radius - blur) >= 0.5f || (radius == 0f && blur != 0f)) {
            blur = radius;
            CoverBlur.set(artwork, radius);
        }
        updatePredraw(visible);
    }

    private void updatePredraw(boolean visible) {
        // Only on a window: a listener put on a view that is not on one moves to the window's observer when it gets
        // there, and the one added again on arrival would be counted twice.
        boolean wanted = visible && overlay != null && isAttachedToWindow();
        if (wanted == predrawing) return;
        ViewTreeObserver observer = getViewTreeObserver();
        if (!observer.isAlive()) return;
        predrawing = wanted;
        if (wanted) observer.addOnPreDrawListener(predraw);
        else observer.removeOnPreDrawListener(predraw);
    }

    /**
     * Where the words go: from under the title block to above the waveform. Worked out from the page's own views after
     * each layout; the margins are set only when they change, so this does not feed back into the layout.
     */
    private void updateGeometry() {
        if (artwork.getHeight() == 0) return;
        int top = (header != null && header.getVisibility() != GONE ? header.getBottom() : artwork.getTop() + Math.round(dp(160)))
                + Math.round(dp(4)) - artwork.getTop();
        int holderTop = artwork.getBottom() - Math.round(dp(120));
        if (holder != null && holder.getHeight() > 0) {
            Rect rect = new Rect(0, 0, holder.getWidth(), holder.getHeight());
            try {
                // The holder is deep inside the page: its position is worked out through the tree.
                page.offsetDescendantRectToMyCoords(holder, rect);
                holderTop = rect.top;
            } catch (IllegalArgumentException ex) {
                // Not inside this page after all; the estimate stands.
            }
        }
        int bottom = artwork.getBottom() - holderTop + Math.round(dp(8));
        boolean room = artwork.getHeight() - top - bottom >= dp(MIN_HEIGHT_DP + (fits ? 0 : ROOM_MARGIN_DP));

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) content.getLayoutParams();
        if (params.topMargin != top || params.bottomMargin != bottom) {
            params.topMargin = Math.max(0, top);
            params.bottomMargin = Math.max(0, bottom);
            content.setLayoutParams(params);
        }
        if (room != fits) {
            fits = room;
            refresh();
        }
    }

    // endregion

    // region Phases

    /** Fades to one of the three pages of the layer (or to none), the others out. */
    private void show(View target) {
        displayed = target;
        if (target != list && list.getVisibility() == VISIBLE) fadeOutUntil = SystemClock.uptimeMillis() + 150;
        View[] pages = {list, status, shimmer};
        for (View view : pages) {
            if (view == target) continue;
            if (view.getVisibility() == GONE) continue;
            final View fading = view;
            fading.animate().cancel();
            fading.animate().alpha(0f).setDuration(140).withEndAction(new Runnable() {
                @Override
                public void run() {
                    if (displayed != fading) {
                        fading.setVisibility(GONE);
                        if (fading == list && lyrics == null) list.setLyrics(null, null, null);
                    }
                }
            }).start();
        }
        if (target != null) {
            target.animate().cancel();
            if (target.getVisibility() != VISIBLE) {
                target.setAlpha(0f);
                target.setVisibility(VISIBLE);
            }
            target.animate().alpha(1f).setDuration(240).setStartDelay(40).start();
        }
    }

    private void setPhase(Phase next) {
        phase = next;
        removeCallbacks(showShimmer);
        if (next == Phase.LOADING) {
            show(null);
            postDelayed(showShimmer, SHIMMER_DELAY_MS);
        }
    }

    /** The answer for a track has come. Pages not showing that track ignore it. */
    void onResult(TrackInfo resultFor, LyricsResult result) {
        if (info == null || !info.sameTrack(resultFor) || phase != Phase.LOADING
                || !info.query().signature.equals(loadedSignature)) {
            return;
        }
        switch (result.status) {
            case FOUND:
                showWords(result.lyrics);
                break;
            case INSTRUMENTAL:
                showMessage(text("Инструментальный трек", "Instrumental track"), false);
                break;
            case MISSING:
                showMessage(text("Текст не найден", "No lyrics found"), false);
                break;
            case OFFLINE:
                showMessage(text("Нет подключения.\nНажмите, чтобы повторить", "No connection.\nTap to try again"), true);
                break;
            default:
                showMessage(text("Не удалось загрузить текст.\nНажмите, чтобы повторить",
                        "Could not load the lyrics.\nTap to try again"), true);
                break;
        }
    }

    private void showWords(final Lyrics words) {
        long wait = fadeOutUntil - SystemClock.uptimeMillis();
        if (wait > 0) {
            // The words of the track before are still fading out; the new ones come when they are gone.
            final String signature = loadedSignature;
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (phase == Phase.LOADING && signature != null && signature.equals(loadedSignature)) applyWords(words);
                }
            }, wait);
            return;
        }
        applyWords(words);
    }

    private void applyWords(Lyrics words) {
        lyrics = words;
        phase = Phase.LIST;
        removeCallbacks(showShimmer);
        Typeface face = title instanceof TextView ? ((TextView) title).getTypeface() : null;
        list.setLyrics(words, face, text("Тексты: LRCLIB", "Lyrics: LRCLIB"));
        status.setClickable(false);
        // Put at the singing line before it is seen, then faded in.
        tick(false);
        show(list);
        updateLoop();
    }

    private void showMessage(String message, final boolean retry) {
        lyrics = null;
        phase = Phase.STATUS;
        removeCallbacks(showShimmer);
        status.setText(message);
        status.setClickable(retry);
        status.setOnClickListener(retry ? new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadedSignature = null;
                refresh();
            }
        } : null);
        show(status);
        updateLoop();
    }

    // endregion

    // region Following the music

    private boolean shouldLoop() {
        return active && isAttachedToWindow() && phase == Phase.LIST && lyrics != null && lyrics.isTimed() && current
                && info != null && getVisibility() == VISIBLE && LyricsClock.isPlaying();
    }

    private void updateLoop() {
        boolean run = shouldLoop();
        if (run && !looping) {
            looping = true;
            Choreographer.getInstance().postFrameCallback(frame);
        } else if (!run && looping) {
            looping = false;
            Choreographer.getInstance().removeFrameCallback(frame);
        }
    }

    /** Lights the line that sings now. */
    private void tick(boolean animate) {
        if (phase != Phase.LIST || lyrics == null || !lyrics.isTimed() || info == null) return;
        long position = LyricsClock.positionFor(info.urn);
        if (position < 0) return;
        list.setActive(lyrics.indexAt(position + LEAD_MS), animate);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        LyricsManager.register(this);
        resync();
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        LyricsManager.unregister(this);
        active = false;
        if (showAnimator != null) showAnimator.cancel();
        showAnimator = null;
        // Back to what the button says, not left in the middle of a fade by the animator that was just stopped.
        shown = LyricsManager.isVisible() ? 1f : 0f;
        removeCallbacks(showShimmer);
        updateLoop();
        if (predrawing) {
            ViewTreeObserver observer = getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(predraw);
            predrawing = false;
        }
        // A page that leaves the screen must not keep the cover blurred when it comes back as another track's.
        if (blur != 0f) {
            blur = 0f;
            CoverBlur.set(artwork, 0f);
        }
    }

    // endregion

    // region Taps on the words

    @Override
    public void onLineTapped(int index) {
        if (lyrics == null || info == null || index < 0 || index >= lyrics.lines.length) return;
        if (!LyricsClock.seekTo(info.urn, lyrics.times[index])) return;
        boolean changed = list.activeLine() != index;
        list.clearHold();
        if (changed) list.setActive(index, true);
        else list.recentre();
    }

    @Override
    public void onBlankTap() {
        passToCover();
    }

    @Override
    public void onDoubleTap() {
        passToCover();
    }

    /** A tap the words do not use is the cover's: SoundCloud pauses and plays on it. */
    private void passToCover() {
        artwork.performClick();
    }

    // endregion
}
