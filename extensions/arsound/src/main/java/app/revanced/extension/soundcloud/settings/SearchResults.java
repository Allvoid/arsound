package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import app.revanced.extension.soundcloud.settings.search.Hit;
import app.revanced.extension.soundcloud.settings.search.Reason;
import app.revanced.extension.soundcloud.settings.search.SearchEntry;
import app.revanced.extension.soundcloud.settings.search.SearchIndex;
import app.revanced.extension.soundcloud.settings.search.Suggestions;
import app.revanced.extension.soundcloud.shared.Colors;

/**
 * What the search shows under the field: the found rows in a card (with the section's icon, the matched words
 * coloured and, when a row was found by meaning or despite a typo, a line saying why), chips with other things to
 * try, and the notes for an empty query or no result. Used by {@link SettingsSearch}.
 */
final class SearchResults {
    private static final int MAX_RESULTS = 30;
    private static final int PARTIAL_RESULTS = 8;
    private static final int ANIMATED_RESULTS = 8;
    private static final int MAX_CHIPS = 6;

    private final SettingsUi ui;
    private final ReVancedSettingsActivity activity;
    private final Map<String, SectionScreen> sectionsById;
    /** Puts a chip's words into the search field. */
    private final Consumer<String> useQuery;
    /** Runs before a result opens its screen (hides the keyboard). */
    private final Runnable beforeOpen;
    private Set<SearchEntry> shown = new HashSet<>();

    SearchResults(SettingsUi ui, Map<String, SectionScreen> sectionsById, Consumer<String> useQuery, Runnable beforeOpen) {
        this.ui = ui;
        this.activity = ui.activity;
        this.sectionsById = sectionsById;
        this.useQuery = useQuery;
        this.beforeOpen = beforeOpen;
    }

    /** Forgets which rows are on screen, so the next results all slide in. */
    void reset() {
        shown.clear();
    }

    /** Fills {@code target} for the query: starting chips, results, or suggestions when nothing was found. */
    void show(LinearLayout target, SearchIndex index, String query) {
        target.removeAllViews();
        if (query.trim().isEmpty() || index == null) {
            reset();
            target.addView(createNote(text("Название настройки или слово из описания. Можно искать по-своему: "
                    + "«память», «фон», «звук».", "A setting's name or a word from its description. Search in your own "
                    + "words: \"storage\", \"background\", \"sound\".")));
            target.addView(createChips(popularQueries()));
            return;
        }
        List<Hit> found = index.search(query, MAX_RESULTS);
        if (found.isEmpty()) {
            reset();
            showNothing(target, index, query);
            return;
        }
        target.addView(createResultsCard(found, true));
        List<String> related = Suggestions.related(index, query, MAX_CHIPS);
        if (!related.isEmpty()) {
            target.addView(createNote(text("Ещё варианты:", "More to try:")));
            target.addView(createChips(related));
        }
    }

    private void showNothing(LinearLayout target, SearchIndex index, String query) {
        target.addView(createNote(text("Ничего не нашлось", "Nothing found")));
        List<String> didYouMean = Suggestions.didYouMean(index, query, 3);
        if (!didYouMean.isEmpty()) {
            target.addView(createNote(text("Возможно, вы искали:", "Did you mean:")));
            target.addView(createChips(didYouMean));
        }
        List<String> related = Suggestions.related(index, query, MAX_CHIPS);
        if (!related.isEmpty()) {
            target.addView(createNote(text("Попробуйте:", "Try:")));
            target.addView(createChips(related));
        }
        List<Hit> partial = index.searchAny(query, PARTIAL_RESULTS);
        if (!partial.isEmpty()) {
            target.addView(ui.createSubHeading(text("Частично подходит", "Partly matches")));
            target.addView(createResultsCard(partial, false));
        }
    }

    private static List<String> popularQueries() {
        return Arrays.asList(
                text("Тема", "Theme"), text("Обои", "Wallpaper"), text("Память", "Storage"), text("Кэш", "Cache"),
                text("Звук", "Sound"), text("Эквалайзер", "Equalizer"), text("Реклама", "Ads"), text("Сеть", "Network"),
                "DNS", text("Батарея", "Battery"), text("Язык", "Language"), text("Уведомления", "Notifications"));
    }

    // region Views

    private View createResultsCard(List<Hit> hits, boolean animate) {
        SettingsGroup card = new SettingsGroup(ui);
        Set<SearchEntry> now = new HashSet<>();
        int animated = 0;
        for (Hit hit : hits) {
            View row = createResult(hit);
            card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            now.add(hit.entry);
            // Rows already on screen stay where they are while the query narrows; only new ones slide in.
            if (animate && !shown.contains(hit.entry) && animated < ANIMATED_RESULTS) {
                row.setAlpha(0f);
                row.setTranslationY(ui.dp(14));
                row.animate().alpha(1f).translationY(0f).setStartDelay(animated * 35L).setDuration(260)
                        .setInterpolator(new DecelerateInterpolator(1.6f)).start();
                animated++;
            }
        }
        if (animate) shown = now;
        LinearLayout.LayoutParams params = SettingsGroup.pageParams(ui);
        params.topMargin = ui.dp(4);
        View result = card;
        if (ui.decor.hasStrips()) result = ui.decor.withStrip(card, ui.decor.stripColor(0));
        else card.setLayoutParams(params);
        return result;
    }

    private View createResult(Hit hit) {
        SearchEntry entry = hit.entry;
        int accent = ui.accent();
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(ui.themeAttribute(android.R.attr.selectableItemBackground));
        row.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));

        SectionScreen section = sectionsById.get(entry.screen);
        Drawable icon = section == null ? null : SettingsIcons.icon(activity, section.iconKey());
        if (icon != null) {
            ImageView tile = new ImageView(activity);
            tile.setImageDrawable(icon);
            tile.setImageTintList(ColorStateList.valueOf(accent));
            tile.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.setPadding(ui.dp(7), ui.dp(7), ui.dp(7), ui.dp(7));
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(ui.dp(9));
            shape.setColor(Colors.withAlpha(accent, 0x2E));
            tile.setBackground(shape);
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32));
            tileParams.rightMargin = ui.dp(14);
            row.addView(tile, tileParams);
        }

        LinearLayout column = ui.createColumn();
        row.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = ui.createText("BodyLarge.Primary", "");
        SpannableString spanned = new SpannableString(entry.title);
        for (int[] range : hit.titleRanges) {
            if (range[0] < 0 || range[1] > entry.title.length() || range[0] >= range[1]) continue;
            spanned.setSpan(new ForegroundColorSpan(accent), range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spanned.setSpan(new StyleSpan(Typeface.BOLD), range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        title.setText(spanned);
        column.addView(title);

        String place = entry.sectionRow ? text("Раздел", "Section") : entry.section;
        String why = reasonText(hit);
        TextView where = ui.createText("Body.Secondary", why.isEmpty() ? place : place + " · " + why);
        where.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        where.setTextColor(Colors.withAlpha(accent, 0xE6));
        where.setPadding(0, ui.dp(2), 0, 0);
        column.addView(where);

        if (!entry.description.isEmpty()) {
            SettingText parts = SettingText.split(entry.description);
            TextView description = ui.createText("Body.Secondary", parts.summary);
            description.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            description.setMaxLines(2);
            description.setEllipsize(TextUtils.TruncateAt.END);
            description.setPadding(0, ui.dp(3), 0, 0);
            column.addView(description);
        }

        row.setOnClickListener(v -> {
            beforeOpen.run();
            Intent open = ui.screenIntent(entry.screen);
            if (!entry.sectionRow) open.putExtra(ReVancedSettingsActivity.EXTRA_HIGHLIGHT, entry.title);
            activity.startActivity(open);
        });
        return row;
    }

    /** Why the row was found, when the words are not simply in it; empty otherwise. */
    private static String reasonText(Hit hit) {
        Reason reason = hit.reason;
        if (reason == Reason.SYNONYM || reason == Reason.RELATED) {
            return text("по слову «" + hit.queryWord + "»", "matches \"" + hit.queryWord + "\"");
        }
        if (reason == Reason.FUZZY) return text("похоже на «" + hit.matchedWord + "»", "looks like \"" + hit.matchedWord + "\"");
        if (reason == Reason.LAYOUT) return text("раскладка: «" + hit.matchedWord + "»", "layout: \"" + hit.matchedWord + "\"");
        return "";
    }

    private View createNote(String message) {
        TextView note = ui.createText("Body.Secondary", message);
        note.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(8));
        note.setAlpha(0f);
        note.animate().alpha(1f).setDuration(240).start();
        return note;
    }

    /** A scrolling row of pills; a tap puts the pill's words into the search field. */
    private View createChips(List<String> labels) {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(4));
        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        int accent = ui.accent();
        for (String label : labels) {
            TextView chip = ui.createText("Body.Primary", label);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(ui.dp(40));
            chip.setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6));
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(ui.dp(100));
            shape.setColor(ui.cardFill());
            shape.setStroke(ui.dp(1), Colors.withAlpha(accent, 0x66));
            chip.setBackground(new RippleDrawable(ColorStateList.valueOf(Colors.withAlpha(accent, 0x33)), shape, null));
            chip.setOnClickListener(v -> useQuery.accept(label));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = ui.dp(8);
            line.addView(chip, params);
        }
        scroll.addView(line);
        return scroll;
    }

    // endregion
}
