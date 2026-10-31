package app.revanced.extension.soundcloud.lyrics;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
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

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.player.PlayerSheet;
import app.revanced.extension.soundcloud.shared.Dimens;
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
final class LyricsLayer extends FrameLayout implements LyricsListView.Host, LyricsAi.Host {
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
    /**
     * The blur comes in this many steps on the way up. Every new radius makes the graphics chip blur the whole cover
     * again, which a frame of the slide cannot afford; in between, the fade of the veil carries the change.
     */
    private static final int BLUR_STEPS = 4;
    /** The words need room: a page whose free middle is shorter than this has no lyrics (a phone on its side). */
    private static final int MIN_HEIGHT_DP = 160;
    /**
     * A page that has lost its lyrics for lack of room needs this much more to get them back. The button itself makes
     * the title block taller, so without the margin a page right at the limit would flip between the two on every layout.
     */
    private static final int ROOM_MARGIN_DP = 60;

    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

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
    /** The offer to recognise a song without lyrics with the neural network, and the progress of it. */
    private final LyricsAi ai;
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
    /** How far the messages are raised above the middle of the page, as a part of its height. */
    private static final float MESSAGE_RISE = 0.2f;
    /** The same in pixels, worked out with the page's geometry. */
    private int lift;
    /** The old words fade out until then; the new ones wait for it. */
    private long fadeOutUntil;
    /** Words that came in the middle of a slide and wait for its end to be laid out; null when none wait. */
    private Lyrics pendingWords;
    private boolean lastEnabled = true;

    private final Runnable showShimmer = new Runnable() {
        @Override
        public void run() {
            if (phase == Phase.LOADING && pendingWords == null) show(shimmer);
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

    /** The page of the player, whatever the view the player hands over wraps it in. */
    static View pageOf(View view) {
        PageIds.load();
        return PageIds.page == 0 ? null : view.findViewById(PageIds.page);
    }

    /** The layer of a page if it has one; never makes one. */
    static LyricsLayer existing(View page) {
        PageIds.load();
        Object tag = PageIds.toggle == 0 ? null : page.getTag(PageIds.toggle);
        return tag instanceof LyricsLayer ? (LyricsLayer) tag : null;
    }

    /** Takes the lyrics button off a page that has no layer: the lyrics are switched off. */
    static void hideButton(View page) {
        PageIds.load();
        View button = PageIds.toggle == 0 ? null : page.findViewById(PageIds.toggle);
        if (button != null && button.getVisibility() != GONE) button.setVisibility(GONE);
    }

    /**
     * The layer a page gets from the player's hooks. Switched off in the settings: a page that has no layer yet gets
     * none (and no button), but one that has a layer keeps being told.
     */
    static LyricsLayer forPage(View page) {
        return LyricsManager.isEnabled() ? of(page) : existing(page);
    }

    /** The layer of a page, made on first use; null if the page has no room for one. */
    static LyricsLayer of(View page) {
        PageIds.load();
        if (PageIds.toggle == 0) return null;
        Object tag = page.getTag(PageIds.toggle);
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
            View button = page.findViewById(PageIds.toggle);
            if (button != null) button.setVisibility(GONE);
        }
        page.setTag(PageIds.toggle, layer == null ? UNSUPPORTED : layer);
        return layer;
    }

    private static LyricsLayer create(View page) {
        if (!(page instanceof RelativeLayout)) return null;
        RelativeLayout parent = (RelativeLayout) page;
        View artwork = page.findViewById(PageIds.artwork);
        View button = page.findViewById(PageIds.toggle);
        View anchor = page.findViewById(PageIds.corner);
        if (anchor == null) anchor = page.findViewById(PageIds.waveform);
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
        this.overlay = page.findViewById(PageIds.overlay);
        this.header = page.findViewById(PageIds.header);
        this.holder = page.findViewById(PageIds.holder);
        this.title = page.findViewById(PageIds.title);
        ColorStateList tint = toggle.getImageTintList();
        offTint = tint != null ? tint
                : ColorStateList.valueOf(ArsoundTheme.isPlayerSkinEnabled() ? 0xFFFFFFFF : 0xFF000000);

        setVisibility(GONE);
        // The cover stays under the layer, so the fade of the layer is all the lyrics need.
        scrim = new View(context);
        scrim.setBackground(scrimDrawable(context));
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        content = new FrameLayout(context) {
            @Override
            public boolean hasOverlappingRendering() {
                // The dim of a pause goes onto each line as it is drawn: an offscreen copy of the whole column on every
                // frame would cost more than the few overlapping pixels it gets right.
                return false;
            }
        };
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

        // Only as big as its text and button, so the rest of the page still passes taps to the cover. Beside the
        // content, not in it: the dim of a pause is the words', and the button must not look switched off.
        ai = new LyricsAi(context, LyricsManager.accent(context), this);
        addView(ai.view(), new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

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
        return Dimens.dpExact(getContext(), value);
    }

    @Override
    public boolean hasOverlappingRendering() {
        // The layer fades on every frame of the slide. Faded as a group, the screen-sized layer would be drawn offscreen
        // first each time; faded piece by piece, the veil only shows a little through the words, which nobody sees.
        return false;
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
        ai.release();
        loadedSignature = null;
        lyrics = null;
        pendingWords = null;
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
        int icon = on ? PageIds.iconOn : PageIds.iconOff;
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
        boolean on = available();
        float shownNow = on ? shown : 0f;
        float alpha = shownNow * clamp((expand - 0.4f) / 0.5f);
        boolean visible = alpha > 0.01f;
        // Hidden on the way down, the layer stays laid out while the lyrics are on: the next way up shows it without a
        // layout of the page in the middle of the slide.
        int visibility = visible ? VISIBLE : on && LyricsManager.isVisible() ? INVISIBLE : GONE;
        if (getVisibility() != visibility) setVisibility(visibility);
        if (visible) {
            setAlpha(alpha);
            float slide = (1f - clamp((shownNow - 0.3f) / 0.7f)) * dp(14);
            content.setTranslationY(slide);
            ai.view().setTranslationY(slide - lift);
        }
        float radius = visible ? dp(BLUR_DP) * (float) Math.ceil(alpha * BLUR_STEPS) / BLUR_STEPS : 0f;
        if (radius != blur) {
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
            FrameLayout.LayoutParams aiParams = (FrameLayout.LayoutParams) ai.view().getLayoutParams();
            aiParams.topMargin = params.topMargin;
            aiParams.bottomMargin = params.bottomMargin;
            ai.view().setLayoutParams(aiParams);
        }
        // The messages stand above the middle of the page: the play button of the cover is there, and a button or a
        // line of text under it cannot be pressed or read.
        int height = Math.max(0, artwork.getHeight() - top - bottom);
        int rise = Math.round(height * MESSAGE_RISE);
        if (rise != lift) {
            lift = rise;
            ai.view().setTranslationY(content.getTranslationY() - lift);
            status.setPadding(status.getPaddingLeft(), status.getPaddingTop(), status.getPaddingRight(),
                    Math.round(dp(16)) + 2 * lift);
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
        View[] pages = {list, status, shimmer, ai.view()};
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
                        if (fading == list && lyrics == null) list.setLyrics(null, null, null, null);
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
        if (result.status == LyricsResult.Status.FOUND) showWords(result.lyrics);
        else if (result.status == LyricsResult.Status.MISSING && ai.offerFor(info)) return;
        // A failed search offers the network as well, the message keeps "tap to try again".
        else if (result.status == LyricsResult.Status.ERROR && ai.offerFor(info, this::searchAgain)) return;
        else showMessage(result.status.message(), result.status.retryable);
    }

    /** Looks the track up again, as a tap on a failure message does. */
    private void searchAgain() {
        loadedSignature = null;
        refresh();
    }

    @Override
    public void showAiPanel() {
        lyrics = null;
        phase = Phase.STATUS;
        removeCallbacks(showShimmer);
        status.setClickable(false);
        show(ai.view());
        updateLoop();
    }

    @Override
    public void showAiWords(Lyrics words) {
        applyWords(words);
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

    private void applyWords(final Lyrics words) {
        if (PlayerSheet.isBusy()) {
            // The column takes a view per line: not built in a frame of the slide (the words of a stored song come right
            // when the sheet passes the middle), but when the sheet rests. Until then the veil and the blur fade in.
            pendingWords = words;
            final String signature = loadedSignature;
            PlayerSheet.afterSlide(new Runnable() {
                @Override
                public void run() {
                    if (pendingWords != words || signature == null || !signature.equals(loadedSignature)) return;
                    applyWords(words);
                }
            });
            return;
        }
        pendingWords = null;
        lyrics = words;
        phase = Phase.LIST;
        removeCallbacks(showShimmer);
        Typeface face = title instanceof TextView ? ((TextView) title).getTypeface() : null;
        // Words a network heard can be heard again, in another language when the first answer was wrong.
        list.setLyrics(words, face, words.source.caption(), words.source == Lyrics.Source.NEURAL ? this::hearAgain : null);
        status.setClickable(false);
        // Put at the singing line before it is seen, then faded in.
        tick(false);
        show(list);
        updateLoop();
    }

    private void hearAgain() {
        if (info != null) ai.redo(info);
    }

    private void showMessage(String message, final boolean retry) {
        lyrics = null;
        phase = Phase.STATUS;
        removeCallbacks(showShimmer);
        status.setText(message);
        // The listener first and the flag last: setOnClickListener() makes a view clickable even when given null, and a
        // clickable message that covers the page would swallow the taps meant for the cover (play and pause).
        status.setOnClickListener(retry ? new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchAgain();
            }
        } : null);
        status.setClickable(retry);
        show(status);
        updateLoop();
    }

    // endregion

    // region Following the music

    private boolean shouldLoop() {
        // The window must be on screen: with the app in the background the views stay attached and the music plays on,
        // and a frame callback at the display's rate would run for nobody.
        return active && isAttachedToWindow() && getWindowVisibility() == VISIBLE && phase == Phase.LIST
                && lyrics != null && lyrics.isTimed() && current && info != null && getVisibility() == VISIBLE
                && LyricsClock.isPlaying();
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
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        try {
            if (visibility == VISIBLE) {
                // Back from the background: the settings or the button may have changed meanwhile, and the player kept
                // playing, so the words are put at the line that sings now before they are seen.
                resync();
                refresh();
                tick(false);
            } else {
                updateLoop();
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Lyrics: could not follow the window showing or hiding", ex);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        LyricsManager.unregister(this);
        active = false;
        // A recognition under way must not hold this page, and the screen it is on, until it ends: the page lets it go.
        // Words on screen stay for a return; the offer or the progress is looked up again then, and finds the work.
        if (phase == Phase.LIST) ai.release();
        else forgetWords();
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
    public void onTap() {
        passToCover();
    }

    @Override
    public void onLineDoubleTapped(int index) {
        // The first tap of the pair paused or played; the second gives that back, so the jump leaves the music as it was.
        passToCover();
        if (lyrics == null || info == null || index < 0 || index >= lyrics.lines.length) return;
        // The words of the page before or after the playing one stay for a swipe: they must not move the track that
        // really plays.
        if (!current) return;
        if (!LyricsClock.seekTo(info.urn, lyrics.times[index])) return;
        boolean changed = list.activeLine() != index;
        list.clearHold();
        if (changed) list.setActive(index, true);
        else list.recentre();
    }

    /** A tap the words do not use is the cover's: SoundCloud pauses and plays on it. */
    private void passToCover() {
        artwork.performClick();
    }

    // endregion
}
