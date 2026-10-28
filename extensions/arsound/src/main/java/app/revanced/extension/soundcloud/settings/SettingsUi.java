package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.AppResources;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.settings.search.SearchEntry;
import app.revanced.extension.soundcloud.shared.HelpBadge;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.wallpaper.WallpaperStore;

/**
 * The views the settings screens are made of: rows, sliders, buttons, the toolbar. Every look is taken from the
 * SoundCloud resources by name, so the screens follow the app, including the dark theme. It holds the Activity (not the
 * application) because styles and theme attributes are read through its context.
 */
final class SettingsUi {
    private static final String SWITCH_CLASS = "com.soundcloud.android.ui.components.toggles.SwitchTransparent";

    final ReVancedSettingsActivity activity;
    final ScreenDecor decor = new ScreenDecor(this);
    final SearchRecorder search = new SearchRecorder();

    SettingsUi(ReVancedSettingsActivity activity) {
        this.activity = activity;
    }

    // region Search

    /**
     * Tells the search about a row, while the screens are built to fill its index. Returns the entry so the row can
     * add words to it, or null when nothing is being collected.
     */
    SearchEntry recordForSearch(String title, String description) {
        return search.record(title, description);
    }

    /** Work that fills the screen with numbers; it has no use while the screens are only read for the search. */
    void fillInBackground(Runnable task) {
        if (!search.isIndexing()) Utils.runOnBackgroundThread(task);
    }

    /** The intent that opens one screen of the settings. */
    Intent screenIntent(String screenId) {
        return new Intent(activity, ReVancedSettingsActivity.class).putExtra(ReVancedSettingsActivity.EXTRA_SCREEN, screenId);
    }

    /** A row that opens another screen; on the main screen it is a section of its own in the search. */
    ActionRow openScreenRow(String title, String description, String screenId) {
        search.beginSectionRow(screenId, title, description);
        try {
            ActionRow row = createActionRow(title, description, v -> activity.startActivity(screenIntent(screenId)));
            row.chevron();
            return row;
        } finally {
            search.endSectionRow();
        }
    }

    /**
     * The row of a section on the main screen: its icon, name, a live one-line summary and an arrow. In the search it
     * is a section of its own.
     */
    ActionRow createSectionRow(SectionScreen section) {
        search.beginSectionRow(section.id(), section.title(), section.description());
        try {
            ActionRow row = new ActionRow(this, section.title(), section.summary(), null);
            row.setSearchEntry(recordForSearch(section.title(), section.description()));
            row.keywords(section.keywords());
            row.icon(section.iconKey());
            row.chevron();
            row.description().setMaxLines(2);
            row.description().setEllipsize(TextUtils.TruncateAt.END);
            row.setOnClickListener(v -> activity.startActivity(screenIntent(section.id())));
            if (!search.isIndexing()) section.loadSummary(row.description());
            return row;
        } finally {
            search.endSectionRow();
        }
    }

    // endregion

    // region Layout

    /** The wallpaper if one is shown, else the theme's surface colour. */
    Drawable screenBackground() {
        Drawable wallpaper = WallpaperStore.background(activity);
        return wallpaper != null ? wallpaper : new ColorDrawable(themeColor("themeColorSurface"));
    }

    /** The root of a screen: the background and room for the system bars. */
    LinearLayout createRoot() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(screenBackground());

        // targetSdk 35+ draws activities edge to edge, so keep the content out of the system bars.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom()
            );
            return insets.consumeSystemWindowInsets();
        });
        return root;
    }

    View createToolbar() {
        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setMinimumHeight(dp(56));
        toolbar.setPadding(dp(4), 0, dimen("spacing_m"), 0);

        ImageButton back = new ImageButton(activity);
        back.setImageResource(Utils.getResourceIdentifier(ResourceType.DRAWABLE, "ic_actions_back_primary"));
        back.setBackgroundResource(themeAttribute(android.R.attr.selectableItemBackgroundBorderless));
        back.setContentDescription(text("Назад", "Back"));
        back.setOnClickListener(v -> activity.onBackPressed());
        int size = dp(48);
        toolbar.addView(back, new LinearLayout.LayoutParams(size, size));

        return toolbar;
    }

    /** A thin gap that ends the card before it: the rows after it start a new card without a heading. */
    View createGroupBreak() {
        View gap = new View(activity);
        gap.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        return gap;
    }

    /** The accent title of a group of rows; the page puts the rows after it into a card. */
    View createSubHeading(String label) {
        return new GroupHeading(this, label);
    }

    /**
     * The top of a section screen: its icon in an accent tile, the title and a one-line description.
     *
     * @param iconKey See {@link SettingsIcons}.
     */
    View createSectionHeader(String iconKey, String title, String summary) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(4), dp(20), dp(12));
        Drawable icon = search.isIndexing() ? null : SettingsIcons.icon(activity, iconKey);
        if (icon != null) {
            ImageView tile = new ImageView(activity);
            tile.setImageDrawable(icon);
            tile.setImageTintList(ColorStateList.valueOf(accent()));
            tile.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.setPadding(dp(10), dp(10), dp(10), dp(10));
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(14));
            shape.setColor(Colors.withAlpha(accent(), 0x2E));
            tile.setBackground(shape);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(44), dp(44));
            params.rightMargin = dp(14);
            header.addView(tile, params);
        }
        LinearLayout texts = createColumn();
        texts.addView(createText("H1.Primary", title));
        if (summary != null && !summary.isEmpty()) {
            TextView line = createText("Body.Secondary", summary);
            line.setPadding(0, dp(2), 0, 0);
            texts.addView(line);
        }
        header.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return header;
    }

    // endregion

    // region Look

    /** The accent colour of the theme. */
    int accent() {
        return ArsoundTheme.palette(activity, "special");
    }

    /** The colour of secondary text and icons. */
    int secondary() {
        return ArsoundTheme.palette(activity, "secondary");
    }

    /** The colour of errors and of what deletes something. */
    int error() {
        int color = ArsoundTheme.palette(activity, "error");
        return color == 0 ? 0xFFE5484D : color;
    }

    /** The fill of a card: the colour of the search field, a little see-through over a wallpaper. */
    int cardFill() {
        int fill = ArsoundTheme.palette(activity, "highlight");
        return WallpaperStore.isActive() ? Colors.withAlpha(fill, 0xD0) : fill;
    }

    /** The thin outline of a card. */
    int cardStroke() {
        return Colors.withAlpha(accent(), 0x2E);
    }

    /** The corner radius of a card in pixels. */
    int cardRadius() {
        return dp(18);
    }

    /** The colour of the lines between rows of a card. */
    int dividerColor() {
        return Colors.withAlpha(themeColor("themeColorPrimary"), 0x1F);
    }

    /** Gives a text view one of SoundCloud's text styles by name; without the style it is just in the primary colour. */
    void style(TextView view, String styleName) {
        int style = Utils.getResourceIdentifier(ResourceType.STYLE, styleName);
        if (style != 0) {
            view.setTextAppearance(style);
        } else {
            view.setTextColor(themeColor("themeColorPrimary"));
        }
    }

    /** An empty vertical list of views. */
    LinearLayout createColumn() {
        LinearLayout group = new LinearLayout(activity);
        group.setOrientation(LinearLayout.VERTICAL);
        return group;
    }

    TextView createText(String styleName, String value) {
        TextView view = new TextView(activity);
        style(view, styleName);
        view.setText(value);
        return view;
    }

    int themeColor(String attributeName) {
        return AppResources.themeColor(activity, Utils.getResourceIdentifier(ResourceType.ATTR, attributeName), 0);
    }

    int themeAttribute(int attribute) {
        TypedValue value = new TypedValue();
        activity.getTheme().resolveAttribute(attribute, value, true);
        return value.resourceId;
    }

    int dimen(String name) {
        int id = Utils.getResourceIdentifier(ResourceType.DIMEN, name);
        return id == 0 ? dp(16) : activity.getResources().getDimensionPixelSize(id);
    }

    int dp(int value) {
        return Dimens.dpTruncated(activity, value);
    }

    /** A small line icon (an arrow, a tick) from points in a unit square. */
    Drawable lineIcon(float[] points, int color) {
        Path path = new Path();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        ShapeDrawable icon = new ShapeDrawable(
                new PathShape(path, 1, 1));
        Paint paint = icon.getPaint();
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.09f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setAntiAlias(true);
        icon.setIntrinsicWidth(dp(24));
        icon.setIntrinsicHeight(dp(24));
        return icon;
    }

    /** Unfolds or folds a drop-down list: the height slides, the arrow turns, the rows appear one after another. */
    void foldOptions(LinearLayout options, View chevron, boolean open) {
        if (options.getTag() instanceof Animator) ((Animator) options.getTag()).cancel();
        int width = ((View) options.getParent()).getWidth();
        options.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int full = options.getMeasuredHeight();
        int from = options.getVisibility() == View.VISIBLE ? options.getHeight() : 0;
        int to = open ? full : 0;
        ViewGroup.LayoutParams params = options.getLayoutParams();
        params.height = from;
        options.setLayoutParams(params);
        options.setVisibility(View.VISIBLE);

        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        animator.setDuration(open ? 320 : 220);
        animator.setInterpolator(new DecelerateInterpolator(open ? 2f : 1.5f));
        animator.addUpdateListener(a -> {
            params.height = (int) a.getAnimatedValue();
            options.setLayoutParams(params);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                options.setTag(null);
                if (cancelled) return;
                if (!open) options.setVisibility(View.GONE);
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                options.setLayoutParams(params);
            }
        });
        options.setTag(animator);
        animator.start();
        chevron.animate().rotation(open ? 180 : 0).setDuration(open ? 320 : 220)
                .setInterpolator(new DecelerateInterpolator()).start();

        // The rows after the divider fade and slide in with a small step between them.
        for (int i = 1; i < options.getChildCount(); i++) {
            View row = options.getChildAt(i);
            row.animate().cancel();
            if (open) {
                row.setAlpha(0f);
                row.setTranslationY(-dp(10));
                row.animate().alpha(1f).translationY(0).setStartDelay(40L * i).setDuration(240)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else {
                row.animate().alpha(0f).setStartDelay(0).setDuration(150).start();
            }
        }
    }

    // endregion

    // region Rows

    ActionRow createActionRow(String title, String description, View.OnClickListener listener) {
        return createActionRow(title, description, null, listener);
    }

    /** @param help A {@link HelpBadge} shown right after the title, or null. */
    ActionRow createActionRow(String title, String description, View help, View.OnClickListener listener) {
        SettingText parts = SettingText.split(description);
        ActionRow row = new ActionRow(this, title, parts.summary, help);
        row.remember(description);
        row.setSearchEntry(recordForSearch(title, description));
        if (parts.details != null) row.details(parts.details);
        row.setOnClickListener(listener);
        return row;
    }

    /**
     * A row that picks one of several values of a long setting. The chosen value stands at the end of the row, the
     * explanation under the title.
     *
     * @param explanation Shown under the title, may be null.
     */
    ActionRow createChoiceRow(String title, String explanation, String key, long defaultValue,
                              long[] values, String[] labels, Runnable onChange) {
        ActionRow[] row = new ActionRow[1];
        row[0] = createActionRow(title, explanation == null ? "" : explanation, v -> new ArsoundDialog(activity)
                .setTitle(title)
                .setSingleChoiceItems(labels, indexOf(values, Settings.getLong(key, defaultValue)), (dialog, which) -> {
                    Settings.putLong(key, values[which]);
                    row[0].value(choiceLabel(key, defaultValue, values, labels));
                    dialog.dismiss();
                    if (onChange != null) onChange.run();
                })
                .show());
        row[0].value(choiceLabel(key, defaultValue, values, labels));
        return row[0];
    }

    private static String choiceLabel(String key, long defaultValue, long[] values, String[] labels) {
        int index = indexOf(values, Settings.getLong(key, defaultValue));
        return index >= 0 ? labels[index] : String.valueOf(Settings.getLong(key, defaultValue));
    }

    private static int indexOf(long[] values, long value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return -1;
    }

    /** A toggle row showing {@code checked}; every change goes to the listener, nothing is stored here. */
    ToggleRow createToggleRow(String title, String description, boolean checked,
                              CompoundButton.OnCheckedChangeListener listener) {
        SettingText parts = SettingText.split(description);
        ToggleRow row = new ToggleRow(this, title, parts.summary);
        row.remember(description);
        row.setSearchEntry(recordForSearch(title, description));
        if (parts.details != null) row.details(parts.details);
        // Every toggle row has the same view id in SoundCloud's layout, so after recreate() Android would restore one
        // saved state into all of them and fire their listeners: turning on "Theme animation" switched it straight
        // back off. The state always comes from the settings, so the switch keeps none of its own (see bind).
        CompoundButton toggle = createSwitch();
        row.addTrailing(toggle, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.bind(toggle, checked, listener);
        return row;
    }

    /** A row bound to a setting: it shows the stored value and stores what the user chooses. */
    ToggleRow createToggleRow(String title, String description, BoolSetting setting) {
        return createToggleRow(title, description, setting, (CompoundButton.OnCheckedChangeListener) null);
    }

    /** As above; {@code then} hears the change after it was stored. May be null. */
    ToggleRow createToggleRow(String title, String description, BoolSetting setting,
                              CompoundButton.OnCheckedChangeListener then) {
        return createToggleRow(title, description, setting.get(), (button, checked) -> {
            setting.set(checked);
            if (then != null) then.onCheckedChanged(button, checked);
        });
    }

    /** A row bound to a setting that shows {@code group} while it is on; the group takes the state of the setting. */
    ToggleRow createToggleRow(String title, String description, BoolSetting setting, View group) {
        return createToggleRow(title, description, setting, group, null);
    }

    /** As above; {@code then} hears the change after it was stored and the group followed. May be null. */
    ToggleRow createToggleRow(String title, String description, BoolSetting setting, View group,
                              CompoundButton.OnCheckedChangeListener then) {
        group.setVisibility(setting.get() ? View.VISIBLE : View.GONE);
        return createToggleRow(title, description, setting, (button, checked) -> {
            group.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (then != null) then.onCheckedChanged(button, checked);
        });
    }

    /** The options of a feature: indented rows that show only while the feature's switch is on. */
    SubRows createSubRows() {
        return new SubRows(this);
    }

    /** SoundCloud's own switch (it follows the app theme), or the platform one if it cannot be made. */
    private CompoundButton createSwitch() {
        CompoundButton toggle;
        try {
            toggle = (CompoundButton) Class.forName(SWITCH_CLASS).getConstructor(Context.class).newInstance(activity);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            toggle = new Switch(activity);
        }
        tintTrack(toggle);
        return toggle;
    }

    /**
     * Gives the switch a track that stands out from the card: the accent when on, a see-through secondary text colour
     * when off (SoundCloud's own off track is almost the colour of the card, so only the thumb would show). The track
     * tint is found by name because SoundCloud's switch is a support-library class this code cannot name; if it has no
     * such method the switch stays as SoundCloud draws it.
     */
    private void tintTrack(CompoundButton toggle) {
        ColorStateList track = new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{accent(), Colors.withAlpha(secondary(), 0x66)});
        try {
            toggle.getClass().getMethod("setTrackTintList", ColorStateList.class).invoke(toggle, track);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // No track tint on this switch: leave it as it is.
        }
    }

    /** A compact row with a tick on the right, for picking several items of a list. */
    ToggleRow createCheckRow(String title, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        ToggleRow row = new ToggleRow(this, title, "");
        row.setMinimumHeight(dp(48));
        row.setSearchEntry(recordForSearch(title, ""));
        CheckBox box = new CheckBox(activity);
        // The accent of the toggles when ticked, the secondary text colour when not.
        box.setButtonTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{accent(), secondary()}));
        row.addTrailing(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.bind(box, checked, listener);
        return row;
    }

    /**
     * A pill-shaped choice of a few labels; the chosen one is filled with the accent.
     *
     * @param select Gets (as its first item) a function that picks a label from outside, without telling {@code onSelect}; or null.
     */
    View createSegmented(String[] labels, int selected, IntConsumer onSelect, IntConsumer[] select) {
        int accent = ArsoundTheme.palette(activity, "special");
        LinearLayout track = new LinearLayout(activity);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable trackShape = new GradientDrawable();
        trackShape.setColor(ArsoundTheme.palette(activity, "highlight"));
        trackShape.setCornerRadius(dp(100));
        track.setBackground(trackShape);
        track.setPadding(dp(3), dp(3), dp(3), dp(3));
        TextView[] items = new TextView[labels.length];
        Runnable[] draw = new Runnable[1];
        int[] chosen = {selected};
        draw[0] = () -> {
            for (int i = 0; i < items.length; i++) {
                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(dp(100));
                pill.setColor(i == chosen[0] ? accent : Color.TRANSPARENT);
                items[i].setBackground(pill);
                items[i].setTextColor(i == chosen[0] ? Colors.contrast(accent) : ArsoundTheme.palette(activity, "secondary"));
            }
        };
        for (int i = 0; i < labels.length; i++) {
            TextView item = createText("H4.Primary", labels[i]);
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setPadding(dp(6), 0, dp(6), 0);
            int index = i;
            item.setOnClickListener(v -> {
                if (chosen[0] == index) return;
                chosen[0] = index;
                draw[0].run();
                onSelect.accept(index);
            });
            items[i] = item;
            track.addView(item, new LinearLayout.LayoutParams(0, dp(38), 1f));
        }
        draw[0].run();
        if (select != null) {
            select[0] = index -> {
                chosen[0] = index;
                draw[0].run();
            };
        }
        return track;
    }

    /**
     * A row with a title, the value at its end and a slider in the accent under it; the description follows the
     * title. Every change of the value is told to {@code onChange}.
     *
     * @param label Writes the value shown at the end of the title line.
     * @param onRelease Told the value when the finger leaves the slider, or null.
     * @param set Gets (as its first item) a function that moves the slider and its value from outside, without telling
     *            {@code onChange}; or null.
     */
    SettingRow createSliderRow(String title, String description, int max, int progress,
                               IntFunction<String> label, IntConsumer onChange,
                               IntConsumer onRelease, IntConsumer[] set) {
        SettingText parts = SettingText.split(description);
        SettingRow row = new SettingRow(this, title, parts.summary);
        row.remember(description);
        row.setSearchEntry(recordForSearch(title, description));
        if (parts.details != null) row.details(parts.details);
        int accent = accent();
        TextView value = row.plainValue(label.apply(progress));

        SeekBar slider = new SeekBar(activity);
        slider.setMax(max);
        slider.setProgress(progress);
        slider.setProgressTintList(ColorStateList.valueOf(accent));
        slider.setThumbTintList(ColorStateList.valueOf(accent));
        slider.setSaveEnabled(false);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                value.setText(label.apply(progress));
                onChange.accept(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                if (onRelease != null) onRelease.accept(bar.getProgress());
            }
        });
        row.addBelow(slider);
        if (set != null) {
            set[0] = newValue -> {
                slider.setProgress(newValue);
                value.setText(label.apply(newValue));
            };
        }
        return row;
    }

    /** A button in the look of the dialogs' buttons: the main one filled with the accent. */
    View createPillButton(String label, boolean main, View.OnClickListener listener) {
        int accent = ArsoundTheme.palette(activity, "special");
        TextView button = createText("H4.Primary", label);
        button.setTextColor(main ? Colors.contrast(accent) : accent);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setPadding(dp(22), dp(8), dp(22), dp(8));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(100));
        shape.setColor(main ? accent : Color.TRANSPARENT);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(
                main ? 0x33FFFFFF : (accent & 0x00FFFFFF) | 0x33000000), shape, null));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    // endregion
}
