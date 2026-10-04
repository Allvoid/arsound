package app.revanced.extension.soundcloud.theme;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

import app.revanced.extension.shared.Logger;

/**
 * The greeting at the top of the home screen ("ПРИВЕТ, ALLVOID / Что послушаем?") of a theme that puts it
 * there: the theme's copy of the home layout names this class. It folds away while the list below is scrolled
 * and comes back at the top of the list.
 */
@SuppressWarnings("unused")
public final class HomeGreeting extends LinearLayout {
    private static final boolean RUSSIAN = "ru".equals(Locale.getDefault().getLanguage());
    private static final long FOLD_MS = 220;

    private int fullHeight = -1;
    private boolean folded;
    private ValueAnimator animator;
    private boolean checkPosted;
    /**
     * The home list scrolls without telling the window (its sections are drawn by Compose), so the greeting
     * looks at it after each frame.
     */
    private final ViewTreeObserver.OnDrawListener drawListener = () -> {
        if (checkPosted) return;
        checkPosted = true;
        post(() -> {
            checkPosted = false;
            onScroll();
        });
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
        String helloText = (RUSSIAN ? "Привет" : "Hello") + (name.isEmpty() ? "" : ", " + name);
        hello.setText(helloText.toUpperCase(Locale.getDefault()));
        hello.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hello.setLetterSpacing(0.08f);
        hello.setTypeface(font(context, "soehne_semi_bold_600"));
        hello.setTextColor(ArsoundTheme.decorColor(decor, "homeHelloColor", themeColor(context, "themeColorSecondary")));
        addView(hello);

        TextView title = new TextView(context);
        title.setText(RUSSIAN ? "Что послушаем?" : "What shall we play?");
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
            int id = context.getResources().getIdentifier(name, "font", context.getPackageName());
            if (id == 0) id = context.getResources().getIdentifier(name, "font", "com.soundcloud.android");
            if (id != 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                return context.getResources().getFont(id);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "HomeGreeting: no font " + name, ex);
        }
        return Typeface.DEFAULT_BOLD;
    }

    private static int themeColor(Context context, String attribute) {
        int id = context.getResources().getIdentifier(attribute, "attr", context.getPackageName());
        if (id == 0) id = context.getResources().getIdentifier(attribute, "attr", "com.soundcloud.android");
        TypedArray array = context.obtainStyledAttributes(new int[]{id});
        try {
            return array.getColor(0, 0xFFFFFFFF);
        } finally {
            array.recycle();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnDrawListener(drawListener);
        // The screen's glow (its background) shows through the bar above: SoundCloud paints the bar's toolbar.
        if (getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) getParent();
            int index = parent.indexOfChild(this);
            if (index > 0) post(() -> clearBackgrounds(parent.getChildAt(index - 1)));
        }
    }

    private static void clearBackgrounds(View view) {
        view.setBackground(null);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i) instanceof ViewGroup) group.getChildAt(i).setBackground(null);
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
        ViewGroup parent = (ViewGroup) getParent();
        boolean fold = false;
        for (int i = parent.indexOfChild(this) + 1; i < parent.getChildCount() && !fold; i++) {
            fold = scrolledDown(parent.getChildAt(i), 0);
        }
        if (fold == folded) return;
        folded = fold;
        if (fullHeight < 0) fullHeight = getHeight();
        int from = getLayoutParams().height >= 0 ? getLayoutParams().height : getHeight();
        int to = fold ? 0 : fullHeight;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofInt(from, to).setDuration(FOLD_MS);
        animator.addUpdateListener(animation -> {
            ViewGroup.LayoutParams params = getLayoutParams();
            params.height = (int) animation.getAnimatedValue();
            setAlpha(fullHeight > 0 ? params.height / (float) fullHeight : 1f);
            setLayoutParams(params);
        });
        animator.start();
    }

    /** True if this view or one inside it (a few levels deep) is a vertical list that has left its top. */
    private static boolean scrolledDown(View view, int depth) {
        if (!view.isShown()) return false;
        if (view.canScrollVertically(-1)) return true;
        if (depth >= 8 || !(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (scrolledDown(group.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }
}
