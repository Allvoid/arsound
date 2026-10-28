package app.revanced.extension.soundcloud.settings;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/** A screen that is a list under a toolbar, in a scroll view. */
abstract class ScrollingScreen extends SettingsScreen {
    private ScrollView screenScroll;

    ScrollingScreen(SettingsUi ui) {
        super(ui);
    }

    /** A screen with the toolbar and a scrolling list. Returns the root; the list is the last child of the scroll view. */
    LinearLayout createScreen(LinearLayout[] listOut) {
        LinearLayout root = ui.createRoot();
        root.addView(ui.createToolbar());

        ScrollView scrollView = new ScrollView(activity);
        screenScroll = scrollView;
        // The page puts the rows that screens add into cards.
        LinearLayout list = new SettingsPage(ui);
        scrollView.addView(list);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        listOut[0] = list;
        return root;
    }

    View createTitle(String title, boolean withLogo) {
        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(ui.dimen("spacing_m"), ui.dimen("spacing_s"), ui.dimen("spacing_m"), ui.dimen("spacing_l"));
        int logoId = withLogo ? Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_icon") : 0;
        if (logoId != 0) {
            ImageView logo = new ImageView(activity);
            logo.setImageResource(logoId);
            LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36));
            logoParams.rightMargin = ui.dp(12);
            // A theme may put the letter on a rounded tile of its accent.
            int badge = ui.decor.badgeColor();
            if (badge != 0) {
                GradientDrawable tile = new GradientDrawable();
                tile.setColor(badge);
                tile.setCornerRadius(ui.dp(15));
                logo.setBackground(tile);
                logo.setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9));
                logoParams = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48));
                logoParams.rightMargin = ui.dp(14);
            }
            titleRow.addView(logo, logoParams);
        }
        titleRow.addView(ui.createText("H1.Primary", title));
        return titleRow;
    }

    /** Scrolls to the row the search led to and lets it glow in the theme's accent a couple of times. */
    @Override
    void revealRow(String title) {
        if (screenScroll == null || screenScroll.getChildCount() == 0) return;
        View content = screenScroll.getChildAt(0);
        View row = findRow(content, title);
        // An entry that is only a heading ("Тема", "Обои") has no row; scroll to the heading instead.
        if (row == null) row = findHeading(content, title);
        if (row == null) return;
        // A row inside a collapsed group of options: show the switch that owns it rather than do nothing.
        if (!row.isShown()) row = owningToggle(row, content);
        if (row == null || !row.isShown()) return;

        int top = 0;
        for (View view = row; view != content && view.getParent() instanceof View; view = (View) view.getParent()) {
            top += view.getTop();
        }
        screenScroll.smoothScrollTo(0, Math.max(0, top - ui.dp(140)));

        ColorDrawable glow = new ColorDrawable(
                ArsoundTheme.palette(activity, "special"));
        glow.setAlpha(0);
        View target = row;
        target.setForeground(glow);
        ValueAnimator pulse = ValueAnimator.ofInt(0, 70, 8, 70, 0);
        pulse.setStartDelay(300);
        pulse.setDuration(1500);
        pulse.addUpdateListener(animation -> glow.setAlpha((Integer) animation.getAnimatedValue()));
        pulse.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                target.setForeground(null);
            }
        });
        pulse.start();
    }

    /** The heading of a card that reads {@code title}, or null. A real row with the same words wins over it. */
    private static View findHeading(View view, String title) {
        if (view instanceof GroupHeading && title.contentEquals(((GroupHeading) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findHeading(group.getChildAt(i), title);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * The switch row just above the collapsed {@link SubRows} that holds {@code row}, or null if the row is not inside
     * such a group. The setting is not switched on: a search tap must not change what the user chose.
     */
    private static View owningToggle(View row, View content) {
        for (View view = row; view != content && view.getParent() instanceof ViewGroup; view = (View) view.getParent()) {
            if (view instanceof SubRows) {
                ViewGroup parent = (ViewGroup) view.getParent();
                int index = parent.indexOfChild(view);
                return index > 0 ? parent.getChildAt(index - 1) : null;
            }
        }
        return null;
    }

    /** The row whose title reads {@code title}: the first one that takes a tap, else any. */
    private static SettingRow findRow(View view, String title) {
        SettingRow any = null;
        if (view instanceof SettingRow && title.contentEquals(((SettingRow) view).title().getText())) {
            SettingRow row = (SettingRow) view;
            if (row.hasOnClickListeners()) return row;
            any = row;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                SettingRow found = findRow(group.getChildAt(i), title);
                if (found == null) continue;
                if (found.hasOnClickListeners()) return found;
                if (any == null) any = found;
            }
        }
        return any;
    }
}
