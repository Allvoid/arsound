package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The window where the artists of "By artists" are picked: a search field over SoundCloud's people, and a list with ticks. Before
 * anything is typed the list offers the artists of the newest likes. Up to {@link WaveArtists#MAX} artists. The answers come from
 * the network on a background thread; the window only draws them.
 */
final class WaveArtistPicker {
    /** Called on the main thread when the artists were saved. */
    interface Done {
        void onSaved(List<WaveArtists.Artist> artists);
    }

    private static final long TYPE_PAUSE_MS = 450;

    private final Context context;
    private final int accent, primary, secondary;
    private final Set<WaveArtists.Artist> picked = new LinkedHashSet<>(WaveArtists.chosen());
    private final List<WaveArtists.Artist> offered = new ArrayList<>();
    private LinearLayout list;
    private TextView note;
    /** The query whose answer is wanted; an answer to an older one is dropped. */
    private String wanted = "";
    private boolean open = true;

    private WaveArtistPicker(Context context) {
        this.context = context;
        accent = ArsoundTheme.palette(context, "special");
        primary = ArsoundTheme.palette(context, "primary");
        secondary = ArsoundTheme.palette(context, "secondary");
    }

    static void show(Context context, Done done) {
        new WaveArtistPicker(context).open(done);
    }

    private void open(Done done) {
        LinearLayout root = new LinearLayout(context);
        root.setTag(ArsoundDialog.KEEP_COLORS);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), 0, dp(20), 0);

        EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setHint(text("Найти музыканта в SoundCloud", "Find an artist on SoundCloud"));
        field.setTextColor(primary);
        field.setHintTextColor(Colors.withAlpha(secondary, 0xB0));
        GradientDrawable plate = new GradientDrawable();
        plate.setColor(Colors.withAlpha(primary, 0x14));
        plate.setCornerRadius(dp(14));
        field.setBackground(plate);
        field.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        note = new TextView(context);
        note.setTextColor(secondary);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(dp(4), dp(10), 0, dp(4));
        root.addView(note);

        ScrollView scroll = new ScrollView(context);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(dp(320), ArsoundDialog.bodyMaxHeight(context))));

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().trim();
                wanted = query;
                // Asked when the typing pauses, not at every letter.
                field.postDelayed(() -> {
                    if (query.equals(wanted)) ask(query);
                }, TYPE_PAUSE_MS);
            }
        });

        new ArsoundDialog(context)
                .setTitle(text("Волна по музыкантам", "A wave by artists"))
                .setView(root)
                .setNegativeButton(text("Отмена", "Cancel"), null)
                .setPositiveButtonKeepingOpen(text("Слушать", "Play"), (dialog, which) -> {
                    if (picked.isEmpty()) {
                        Toast.makeText(context, text("Отметьте хотя бы одного музыканта", "Tick at least one artist"),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<WaveArtists.Artist> artists = new ArrayList<>(picked);
                    WaveArtists.save(artists);
                    dialog.dismiss();
                    done.onSaved(artists);
                })
                .setOnDismissListener(dialog -> open = false)
                .show();
        ask("");
    }

    /** The artists for a query, or those of the likes for none; drawn when they come. */
    private void ask(String query) {
        if (!open) return;
        note.setText(text("Ищу…", "Looking…"));
        Utils.runOnBackgroundThread(() -> {
            List<WaveArtists.Artist> found = query.isEmpty() ? WaveArtists.fromLikes() : WaveArtists.search(query);
            Utils.runOnMainThread(() -> {
                if (!open || !query.equals(wanted)) return;
                offered.clear();
                offered.addAll(found);
                note.setText(found.isEmpty()
                        ? text("Никого не нашлось", "Nobody found")
                        : query.isEmpty() ? text("Из ваших лайков", "From your likes") : text("В SoundCloud", "On SoundCloud"));
                draw();
            });
        });
    }

    /** The picked artists first, then the ones offered that are not picked. */
    private void draw() {
        list.removeAllViews();
        Set<WaveArtists.Artist> rows = new LinkedHashSet<>(picked);
        rows.addAll(offered);
        for (WaveArtists.Artist artist : rows) list.addView(row(artist));
    }

    private CheckBox row(WaveArtists.Artist artist) {
        CheckBox box = new CheckBox(context);
        box.setText(artist.followers > 0
                ? artist.name + "  ·  " + WaveArtists.compact(artist.followers) + " " + text("подписчиков", "followers")
                : artist.name);
        box.setTextColor(primary);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        box.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{accent, secondary}));
        box.setChecked(picked.contains(artist));
        box.setPadding(dp(4), dp(6), 0, dp(6));
        box.setOnCheckedChangeListener((view, checked) -> {
            if (!checked) {
                picked.remove(artist);
                return;
            }
            if (picked.size() >= WaveArtists.MAX) {
                view.setChecked(false);
                Toast.makeText(context, text("Не больше " + WaveArtists.MAX + " музыкантов", "At most " + WaveArtists.MAX + " artists"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            picked.add(artist);
        });
        return box;
    }

    private int dp(int value) {
        return Dimens.dp(context, value);
    }
}
