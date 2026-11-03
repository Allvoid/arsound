package app.revanced.extension.soundcloud.theme;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.player.PlayerSheet;
import app.revanced.extension.soundcloud.settings.BoolSettings;
import app.revanced.extension.soundcloud.shared.AppResources;
import app.revanced.extension.soundcloud.shared.ClearBackground;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.shared.Lang;

/**
 * The greeting at the top of the home screen ("ПРИВЕТ, &lt;имя&gt; / Что послушаем?") of a theme that puts it
 * there: the theme's copy of the home layout names this class. It folds away while the list below is scrolled
 * and comes back at the top of the list. A setting hides it.
 * <p>
 * With a wallpaper the home screen is not painted by this class: the picture is the window's, and the screen's
 * surface fill is taken away like on every main screen (wallpaper/WallpaperSurfaces).
 */
@SuppressWarnings("unused")
public final class HomeGreeting extends LinearLayout {
    private static final long FOLD_MS = 220;
    /** The shortest time between two looks at the list. */
    private static final long CHECK_EVERY_MS = 100;

    private int fullHeight = -1;
    private boolean folded;
    private ValueAnimator animator;
    private boolean checkPosted;
    private long checkedAt;
    /**
     * The home list scrolls without telling the window (its sections are drawn by Compose), so the greeting
     * looks at it after the frames. With the smooth player collapse on, not after every one: the walk through
     * the list is made at most every {@link #CHECK_EVERY_MS}, a little later when a frame came too soon, so the
     * last position is always seen. And not at all while the player slides: the list does not move then.
     */
    private final ViewTreeObserver.OnDrawListener drawListener = () -> {
        if (checkPosted || PlayerSheet.isBusy()) return;
        checkPosted = true;
        long wait = PlayerSheet.isSmoothEnabled()
                ? Math.max(0, checkedAt + CHECK_EVERY_MS - SystemClock.uptimeMillis()) : 0;
        postDelayed(() -> {
            checkPosted = false;
            checkedAt = SystemClock.uptimeMillis();
            onScroll();
        }, wait);
    };

    public HomeGreeting(Context context) {
        this(context, null);
    }

    public HomeGreeting(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        JSONObject decor = ArsoundTheme.decor(context);

        TextView hello = new TextView(context);
        String name = accountName(context);
        String helloText = Lang.text("Привет", "Hello") + (name.isEmpty() ? "" : ", " + name);
        hello.setText(helloText.toUpperCase(Locale.getDefault()));
        hello.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hello.setLetterSpacing(0.08f);
        hello.setTypeface(font(context, "soehne_semi_bold_600"));
        hello.setTextColor(ArsoundTheme.decorColor(decor, "homeHelloColor", themeColor(context, "themeColorSecondary")));
        addView(hello);

        TextView title = new TextView(context);
        title.setText(Lang.text("Что послушаем?", "What shall we play?"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        title.setTypeface(font(context, "soehne_extrafett_900"));
        title.setTextColor(themeColor(context, "themeColorPrimary"));
        title.setIncludeFontPadding(false);
        // Wide heading fonts (Unbounded) shrink to keep the question on one line.
        title.setMaxLines(1);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            title.setAutoSizeTextTypeUniformWithConfiguration(20, 32, 1, TypedValue.COMPLEX_UNIT_SP);
        }
        LayoutParams titleParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(4);
        addView(title, titleParams);

        setPadding(dp(16), dp(20), dp(16), dp(6));
    }

    /** The name of the signed-in SoundCloud account, as Android keeps it for the app; empty if none. */
    private static String accountName(Context context) {
        try {
            String own = context.getPackageName();
            for (Account account : AccountManager.get(context).getAccounts()) {
                if (account.type.startsWith(own) || account.type.equals("com.soundcloud.android.account")) {
                    String name = account.name;
                    int at = name.indexOf('@');
                    if (at > 0) name = name.substring(0, at);
                    // SoundCloud names the account "<username>-<user id>".
                    return name.replaceFirst("-\\d+$", "");
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "HomeGreeting: no account name", ex);
        }
        return "";
    }

    private static Typeface font(Context context, String name) {
        try {
            int id = AppResources.identifier(context, name, "font");
            if (id != 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                return context.getResources().getFont(id);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "HomeGreeting: no font " + name, ex);
        }
        return Typeface.DEFAULT_BOLD;
    }

    private static int themeColor(Context context, String attribute) {
        return AppResources.themeColor(context, AppResources.identifier(context, attribute, "attr"), 0xFFFFFFFF);
    }

    private int dp(int value) {
        return Dimens.dp(getContext(), value);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Hidden by the setting: no room taken, and no looking at the list after each frame.
        boolean hidden = BoolSettings.HIDE_HOME_GREETING.get();
        setVisibility(hidden ? GONE : VISIBLE);
        if (!hidden) getViewTreeObserver().addOnDrawListener(drawListener);
        // The screen's glow (its background) and the wallpaper show through the bar above: SoundCloud paints the bar's
        // toolbar.
        if (getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) getParent();
            int index = parent.indexOfChild(this);
            if (index > 0) post(() -> {
                // The parent may have lost children before this runs.
                View above = parent.getChildAt(index - 1);
                if (above != null) clearBackgrounds(above);
            });
        }
    }

    private static void clearBackgrounds(View view) {
        ClearBackground.on(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i) instanceof ViewGroup) ClearBackground.on(group.getChildAt(i));
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnDrawListener(drawListener);
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    /** Folds the greeting once the list below has left its top, unfolds it back at the top. */
    private void onScroll() {
        if (!(getParent() instanceof ViewGroup)) return;
        boolean fold = listScrolledDown((ViewGroup) getParent());
        if (fold == folded) return;
        folded = fold;
        if (fullHeight < 0) fullHeight = getHeight();
        // A fold still running jumps to its end, so the next one starts from a laid-out state.
        if (animator != null) animator.end();
        startFold(fold);
    }

    // region Looking at the list

    /** The whole list below is walked again at most this often; in between only the lists it found are asked. */
    private static final long WALK_EVERY_MS = 1_000;
    /** The views below that can scroll vertically, found by the last walk. */
    private final List<WeakReference<View>> scrollables = new ArrayList<>();
    private long walkedAt = -WALK_EVERY_MS;

    /**
     * True if a list below the greeting has left its top. The walk through the views below, up to eight levels deep,
     * ran after every drawn frame of the home screen, and the theme effect draws frames all the time: now the lists
     * it finds are kept, and only they are asked between two walks.
     */
    private boolean listScrolledDown(ViewGroup parent) {
        long now = SystemClock.uptimeMillis();
        if (now - walkedAt >= WALK_EVERY_MS) {
            walkedAt = now;
            scrollables.clear();
            for (int i = parent.indexOfChild(this) + 1; i < parent.getChildCount(); i++) {
                findScrollables(parent.getChildAt(i), 0);
            }
        }
        for (WeakReference<View> reference : scrollables) {
            View list = reference.get();
            if (list != null && list.isShown() && list.canScrollVertically(-1)) return true;
        }
        return false;
    }

    /** Keeps this view, or the views inside it a few levels deep, that scroll vertically; a list is not looked into. */
    private void findScrollables(View view, int depth) {
        if (!view.isShown()) return;
        if (view.canScrollVertically(-1) || view.canScrollVertically(1)) {
            scrollables.add(new WeakReference<>(view));
            return;
        }
        if (depth >= 8 || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) findScrollables(group.getChildAt(i), depth + 1);
    }

    // endregion

    // region Folding

    private final Rect clip = new Rect();

    /**
     * Folds or unfolds with two layouts, one at the start and one at the end. In between the views below slide with
     * a translation over the greeting, which is cut at their top edge and fades. The greeting's height changed on
     * every frame before, and the whole home list below was laid out again with it each frame.
     * <p>
     * For the slide the views below get a fixed height that keeps their bottom edge below the screen's, so no gap
     * opens under them; their own height comes back with the last layout.
     */
    private void startFold(boolean fold) {
        ViewGroup parent = (ViewGroup) getParent();
        int full = fullHeight;
        if (full <= 0 || parent == null) {
            setHeight(fold ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT);
            return;
        }
        List<View> below = new ArrayList<>();
        for (int i = parent.indexOfChild(this) + 1; i < parent.getChildCount(); i++) below.add(parent.getChildAt(i));
        int[] heights = new int[below.size()];
        for (int i = 0; i < below.size(); i++) {
            View view = below.get(i);
            ViewGroup.LayoutParams params = view.getLayoutParams();
            heights[i] = params.height;
            // Folding: the views keep their place and grow by the greeting, then slide up over it.
            // Unfolding: the greeting takes its room, and the views slide down from where they were.
            params.height = fold ? view.getHeight() + full : view.getHeight();
            view.setLayoutParams(params);
        }
        if (!fold) setHeight(full);
        float start = fold ? 0 : -full;
        float end = fold ? -full : 0;
        slide(below, full, start);
        animator = ValueAnimator.ofFloat(start, end).setDuration(FOLD_MS);
        animator.addUpdateListener(animation -> slide(below, full, (float) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                for (int i = 0; i < below.size(); i++) {
                    View view = below.get(i);
                    view.setTranslationY(0);
                    ViewGroup.LayoutParams params = view.getLayoutParams();
                    params.height = heights[i];
                    view.setLayoutParams(params);
                }
                setClipBounds(null);
                setAlpha(fold ? 0f : 1f);
                if (fold) setHeight(0);
            }
        });
        animator.start();
    }

    /** One frame of the slide: no layout, only translations, the greeting's cut and its fade. */
    private void slide(List<View> below, int full, float shift) {
        for (View view : below) view.setTranslationY(shift);
        int visible = Math.max(0, Math.round(full + shift));
        clip.set(0, 0, getWidth(), visible);
        setClipBounds(clip);
        setAlpha(visible / (float) full);
    }

    private void setHeight(int height) {
        ViewGroup.LayoutParams params = getLayoutParams();
        if (params.height == height) return;
        params.height = height;
        setLayoutParams(params);
    }

    /** The two lines do not overlap: a fade is drawn on each of them, without a layer for the whole greeting. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    // endregion
}
