package app.revanced.extension.soundcloud.search;

import static app.revanced.extension.soundcloud.shared.Colors.withAlpha;
import static app.revanced.extension.soundcloud.shared.Dimens.dpTruncated;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The same tabs as SoundCloud's own search results: text, a line under the selected one and a thin line under the row.
 * SoundCloud draws them in Compose, so they are made here the same way, with its labels (the app has no Russian
 * translation of them).
 */
final class ResultTabs {
    static final int ALL = 0;
    static final int TRACKS = 1;
    static final int ARTISTS = 2;
    static final int ALBUMS = 3;

    /** SoundCloud's own labels, in English as its search shows them (it has no Russian for them): the halves match. */
    private static String[] labels() {
        return new String[]{"All", "Tracks", "Profiles", "Albums"};
    }

    /** Called when the user picks another tab. */
    interface Listener {
        void onTabChanged();
    }

    private final int textColor;
    private final LinearLayout bar;
    private final LinearLayout tabs;
    private int selected = ALL;

    ResultTabs(Context context, int textColor, Listener listener) {
        this.textColor = textColor;
        bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.VERTICAL);
        tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = labels();
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            LinearLayout tabView = new LinearLayout(context);
            tabView.setOrientation(LinearLayout.VERTICAL);
            tabView.setGravity(Gravity.CENTER_HORIZONTAL);
            tabView.setOnClickListener(v -> {
                if (selected == index) return;
                selected = index;
                style();
                listener.onTabChanged();
            });
            TextView label = new TextView(context);
            label.setText(labels[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            tabView.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            View indicator = new View(context);
            indicator.setBackgroundColor(textColor);
            tabView.addView(indicator, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpTruncated(context, 2)));
            tabs.addView(tabView, new LinearLayout.LayoutParams(0, dpTruncated(context, 52), 1));
        }
        bar.addView(tabs);
        View divider = new View(context);
        divider.setBackgroundColor(withAlpha(textColor, 0x26));
        bar.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpTruncated(context, 1)));
        style();
    }

    View view() {
        return bar;
    }

    /** The selected tab: {@link #ALL}, {@link #TRACKS}, {@link #ARTISTS} or {@link #ALBUMS}. */
    int selected() {
        return selected;
    }

    private void style() {
        for (int i = 0; i < tabs.getChildCount(); i++) {
            LinearLayout tabView = (LinearLayout) tabs.getChildAt(i);
            boolean isSelected = i == selected;
            ((TextView) tabView.getChildAt(0)).setTextColor(isSelected ? textColor : withAlpha(textColor, 0xA0));
            tabView.getChildAt(1).setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
        }
    }
}
