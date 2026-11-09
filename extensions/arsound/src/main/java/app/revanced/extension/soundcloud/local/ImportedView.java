package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Colors.contrast;
import static app.revanced.extension.soundcloud.shared.Colors.withAlpha;
import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * Search and order of the "Imported" playlist. Set in one window from the playlist's "⋮" ("Search and sort"), so
 * the screen gets no new button; the line under the playlist's title says when a search or an order is on.
 * <p>
 * The search lasts while the app runs (a found list must not greet the user days later); the order is kept. Both
 * are applied to the track list SoundCloud asks for ({@link LocalAdditions}), for the screen and for playback alike,
 * so the track tapped is the track that plays. Only the tags kept by {@link FileTags} are read, never the files.
 */
final class ImportedView {
    private static final String PREFERENCES = "arsound_imported_view";
    private static final String SORT = "sort";

    private static volatile String query = "";
    private static volatile boolean losslessOnly;
    private static volatile ImportedQuery.Sort sort;
    /** SoundCloud tracks whose title was asked for once while the app runs: a track with none is not asked again. */
    private static final java.util.Set<String> ASKED_TITLES = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<>());

    private ImportedView() {
    }

    private static ImportedQuery.Sort sort() {
        ImportedQuery.Sort current = sort;
        if (current != null) return current;
        current = ImportedQuery.Sort.ADDED;
        SharedPreferences preferences = LocalPrefs.open(PREFERENCES);
        if (preferences != null) {
            try {
                current = ImportedQuery.Sort.valueOf(preferences.getString(SORT, current.name()));
            } catch (IllegalArgumentException ignored) {
                // A name of a later version: the order of adding.
            }
        }
        sort = current;
        return current;
    }

    /** Whether the playlist is shown other than as it is. */
    static boolean isActive() {
        return !query.isEmpty() || losslessOnly || sort() != ImportedQuery.Sort.ADDED;
    }

    /**
     * The tracks of "Imported" to show and play, in their order.
     *
     * @param ordered The {@code List<Urn>} of the playlist, in its own order.
     */
    static Object apply(Object ordered) {
        if (!isActive() || !(ordered instanceof List)) return ordered;
        List<?> urns = (List<?>) ordered;
        List<ImportedQuery.Item> items = new ArrayList<>(urns.size());
        List<String> untitled = new ArrayList<>();
        for (Object urn : urns) {
            ImportedQuery.Item item = item(urn);
            if (!query.isEmpty() && !Urns.isLocalFile(urn) && item.title.isEmpty()
                    && ASKED_TITLES.add(String.valueOf(urn))) {
                untitled.add(String.valueOf(urn));
            }
            items.add(item);
        }
        // SoundCloud tracks in "Imported" are searched by title: titles not known yet are loaded once, then the list
        // is shown again with them.
        if (!untitled.isEmpty()) TrackTitles.rememberAll(untitled, ImportedView::refresh);
        List<Object> result = ImportedQuery.apply(items, query, losslessOnly, sort());
        Logger.printInfo(() -> "Imported: " + result.size() + " of " + urns.size() + " shown (search \"" + query
                + "\", lossless only " + losslessOnly + ", order " + sort() + ")");
        return result;
    }

    private static ImportedQuery.Item item(Object urn) {
        if (!Urns.isLocalFile(urn)) return ImportedQuery.Item.other(urn, TrackTitles.get(String.valueOf(urn)));
        File file;
        try {
            file = Urns.fileOf(urn);
        } catch (Exception ex) {
            return ImportedQuery.Item.other(urn, null);
        }
        FileTags.Tags tags = FileTags.of(file);
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (tags == null) {
            return new ImportedQuery.Item(urn, false, base, "", "", name, 0, 0, 0, FileTags.isLossless(file));
        }
        return new ImportedQuery.Item(urn, true, tags.title.isEmpty() ? base : tags.title, tags.artist, tags.album, name,
                tags.year, tags.track, tags.bitRate, FileTags.isLossless(file));
    }

    /** The tags of new files were read: a search or order that is on is applied again with them. */
    static void onTagsRead() {
        if (isActive()) Utils.runOnMainThread(ImportedView::refresh);
    }

    /** Shows "Imported" again. Main thread. */
    private static void refresh() {
        String urn = SavedPlaylist.getUrn();
        if (urn != null) PlaylistChanges.playlistChanged(urn);
    }

    /** The part of the line under the playlist's title while a search or an order is on; null otherwise. */
    static String headerPart() {
        if (!isActive()) return null;
        StringBuilder part = new StringBuilder();
        if (!query.isEmpty()) part.append("🔍 «").append(query).append('»');
        if (losslessOnly) {
            if (part.length() > 0) part.append(' ');
            part.append(text("без потерь", "lossless"));
        }
        if (sort() != ImportedQuery.Sort.ADDED) {
            if (part.length() > 0) part.append(' ');
            part.append("↕ ").append(sortLabel(sort()).toLowerCase(java.util.Locale.getDefault()));
        }
        return part.toString();
    }

    private static String sortLabel(ImportedQuery.Sort sort) {
        switch (sort) {
            case TITLE:
                return text("Название", "Title");
            case ARTIST:
                return text("Исполнитель", "Artist");
            case ALBUM:
                return text("Альбом", "Album");
            case YEAR:
                return text("Год", "Year");
            case BITRATE:
                return text("Битрейт", "Bit rate");
            default:
                return text("Добавлены", "Added");
        }
    }

    private static void set(String newQuery, boolean newLosslessOnly, ImportedQuery.Sort newSort) {
        boolean changed = !newQuery.equals(query) || newLosslessOnly != losslessOnly || newSort != sort();
        query = newQuery;
        losslessOnly = newLosslessOnly;
        sort = newSort;
        SharedPreferences preferences = LocalPrefs.open(PREFERENCES);
        if (preferences != null) preferences.edit().putString(SORT, newSort.name()).apply();
        if (!changed) return;
        String urn = SavedPlaylist.getUrn();
        if (urn != null) PlaylistChanges.playlistChanged(urn);
        // The tags of files not read yet are read now, and the list is put right after.
        Utils.runOnBackgroundThread(FileTags::readAll);
    }

    // region Window

    /** The window: a search field, "lossless only" and the orders. */
    static void show(Context context) {
        new Window(context).show();
    }

    private static final class Window {
        private final Context context;
        private final int accent, primary, secondary;
        private final List<TextView> chips = new ArrayList<>();
        private ImportedQuery.Sort chosen = sort();

        Window(Context context) {
            this.context = context;
            accent = ArsoundTheme.palette(context, "special");
            primary = ArsoundTheme.palette(context, "primary");
            secondary = ArsoundTheme.palette(context, "secondary");
        }

        void show() {
            // The tags are read while the window is open, so the first search finds them.
            Utils.runOnBackgroundThread(FileTags::readAll);
            LinearLayout root = new LinearLayout(context);
            root.setTag(ArsoundDialog.KEEP_COLORS);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(20), 0, dp(20), 0);

            EditText field = new EditText(context);
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT);
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            field.setHint(text("Название, исполнитель, альбом", "Title, artist, album"));
            field.setText(query);
            field.setSelection(field.getText().length());
            field.setTextColor(primary);
            field.setHintTextColor(withAlpha(secondary, 0xB0));
            field.setBackground(rounded(withAlpha(primary, 0x14), 14));
            field.setPadding(dp(14), dp(10), dp(14), dp(10));
            root.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            CheckBox lossless = new CheckBox(context);
            lossless.setText(text("Только без потерь (FLAC, ALAC, WAV)", "Lossless only (FLAC, ALAC, WAV)"));
            lossless.setTextColor(primary);
            lossless.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            lossless.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{accent, secondary}));
            lossless.setChecked(losslessOnly);
            LinearLayout.LayoutParams losslessParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            losslessParams.topMargin = dp(8);
            root.addView(lossless, losslessParams);

            TextView heading = new TextView(context);
            heading.setText(text("Порядок", "Order"));
            heading.setTextColor(secondary);
            heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            heading.setPadding(dp(4), dp(10), 0, dp(6));
            root.addView(heading);

            ImportedQuery.Sort[] sorts = ImportedQuery.Sort.values();
            for (int start = 0; start < sorts.length; start += 3) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int i = start; i < Math.min(start + 3, sorts.length); i++) row.addView(chip(sorts[i]));
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowParams.bottomMargin = dp(6);
                root.addView(row, rowParams);
            }
            paintChips();

            ArsoundDialog dialog = new ArsoundDialog(context)
                    .setTitle(text("Поиск и сортировка", "Search and sort"))
                    .setView(root)
                    .setNegativeButton(text("Отмена", "Cancel"), null)
                    .setPositiveButton(text("Показать", "Show"), (d, which) ->
                            set(field.getText().toString().trim(), lossless.isChecked(), chosen));
            if (isActive()) {
                dialog.setNeutralButton(text("Сбросить", "Reset"), (d, which) -> set("", false, ImportedQuery.Sort.ADDED));
            }
            android.app.Dialog shown = dialog.show();
            field.setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_SEARCH) return false;
                set(field.getText().toString().trim(), lossless.isChecked(), chosen);
                shown.dismiss();
                return true;
            });
        }

        private TextView chip(ImportedQuery.Sort sort) {
            TextView chip = new TextView(context);
            chip.setText(sortLabel(sort));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setPadding(dp(4), dp(8), dp(4), dp(8));
            chip.setTag(sort);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            params.leftMargin = dp(3);
            params.rightMargin = dp(3);
            chip.setLayoutParams(params);
            chip.setOnClickListener(v -> {
                chosen = sort;
                paintChips();
            });
            chips.add(chip);
            return chip;
        }

        private void paintChips() {
            for (TextView chip : chips) {
                boolean selected = chip.getTag() == chosen;
                GradientDrawable shape = rounded(selected ? accent : Color.TRANSPARENT, 100);
                if (!selected) shape.setStroke(Math.max(1, dp(1)), withAlpha(primary, 0x40));
                chip.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 0x33)), shape, null));
                chip.setTextColor(selected ? contrast(accent) : primary);
            }
        }

        private GradientDrawable rounded(int color, float radius) {
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(radius));
            shape.setColor(color);
            return shape;
        }

        private int dp(float value) {
            return Dimens.dp(context, value);
        }
    }

    // endregion
}
