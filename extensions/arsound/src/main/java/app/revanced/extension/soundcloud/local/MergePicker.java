package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.local.LocalText.tracks;
import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.shared.ReorderColumn;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The window of "Merge playlists": the playlist that gets the tracks is pinned at the top, the playlists the
 * user ticked are in a column above the others and are dragged by their handles into the order their tracks are
 * added in. A ticked playlist goes to the end of that column, an unticked one back to its place in the library.
 * <p>
 * The merge button stays dim until a playlist is ticked.
 */
final class MergePicker {
    /** Called with the ticked playlists in the order the user put them in. */
    interface Listener {
        void onMerge(List<MergeCandidate> sources);
    }

    private static final int ROW_DP = 56;
    private static final int COVER_DP = 40;
    private static final int MIN_CACHE_BYTES = 3 * 1024 * 1024;
    private static final int MAX_CACHE_BYTES = 12 * 1024 * 1024;
    /** What a cover takes in the cache, about: SoundCloud's small pictures are 100 by 100 pixels. */
    private static final int COVER_CACHE_BYTES = 64 * 1024;
    /** The covers of the last lists, so a dialog opened again does not load them again. Sized to the library when a picker opens. */
    private static final LruCache<String, Bitmap> THUMBNAILS = new LruCache<String, Bitmap>(MIN_CACHE_BYTES) {
        @Override
        protected int sizeOf(String address, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    /** One pool for every picker, whose threads end by themselves when no cover is asked for: a picker that is never closed leaks nothing. */
    private static final ThreadPoolExecutor COVERS = new ThreadPoolExecutor(3, 3, 10, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), task -> {
        Thread thread = new Thread(task, "arsound-merge-covers");
        thread.setDaemon(true);
        return thread;
    });

    static {
        COVERS.allowCoreThreadTimeOut(true);
    }

    private final Context context;
    private final MergeLibrary library;
    private final Listener listener;
    private final float density;
    private final int accent, primary, secondary, highlight, error, surface;
    private final List<Row> rows = new ArrayList<>();

    private Dialog dialog;
    /** Set once the dialog is gone: the covers still waiting for a thread are dropped. */
    private volatile boolean closed;
    private ReorderColumn queue;
    private LinearLayout pool;
    private CappedScroll scroll;
    private TextView queueTitle, poolTitle, summary;

    MergePicker(Context context, MergeLibrary library, Listener listener) {
        this.context = context;
        this.library = library;
        this.listener = listener;
        density = context.getResources().getDisplayMetrics().density;
        accent = ArsoundTheme.palette(context, "special");
        primary = ArsoundTheme.palette(context, "primary");
        secondary = ArsoundTheme.palette(context, "secondary");
        highlight = ArsoundTheme.palette(context, "highlight");
        error = ArsoundTheme.palette(context, "error");
        surface = ArsoundTheme.palette(context, "dialog");
    }

    /** One playlist of the library: its row stays the same view, in the pool or in the column of ticked ones. */
    private final class Row {
        final MergeCandidate candidate;
        final LinearLayout view;
        final TickBox box;
        final View handle;
        final ImageView cover;
        /** The picture is asked for once the row comes near the visible part of the list, not for the whole library at once. */
        boolean coverRequested;
        boolean ticked;
        /** The tick is shown before the row moves, so a tap is seen to land. */
        boolean moving;

        Row(MergeCandidate candidate) {
            this.candidate = candidate;
            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setMinimumHeight(dp(ROW_DP));
            view.setPadding(dp(20), dp(6), dp(4), dp(6));
            view.setBackground(ripple());
            view.setOnClickListener(v -> toggle(this));

            box = new TickBox(context, accent, secondary);
            LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(dp(20), dp(20));
            boxParams.rightMargin = dp(14);
            view.addView(box, boxParams);

            cover = coverView();
            LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(dp(COVER_DP), dp(COVER_DP));
            coverParams.rightMargin = dp(12);
            view.addView(cover, coverParams);

            view.addView(labels(candidate.title, subtitle(candidate)), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            handle = ReorderColumn.createHandle(context, secondary);
            handle.setVisibility(View.GONE);
            view.addView(handle, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT));
        }

        void setTicked(boolean ticked) {
            this.ticked = ticked;
            box.setChecked(ticked);
            handle.setVisibility(ticked ? View.VISIBLE : View.GONE);
        }

        void loadCoverIfNear() {
            if (coverRequested || !isNearViewport(view)) return;
            coverRequested = true;
            loadCover(cover, candidate);
        }
    }

    private String subtitle(MergeCandidate candidate) {
        String count = candidate.count < 0 ? "" : tracks(candidate.count);
        return candidate.album ? text("Альбом", "Album") + (count.isEmpty() ? "" : " · " + count) : count;
    }

    // region Window

    void show() {
        for (MergeCandidate candidate : library.sources) rows.add(new Row(candidate));
        // Room for the covers of the whole library, so opening the window again does not load them again.
        THUMBNAILS.resize(Math.max(MIN_CACHE_BYTES, Math.min(MAX_CACHE_BYTES, rows.size() * COVER_CACHE_BYTES)));

        PickerContent content = new PickerContent(context);
        content.setOrientation(LinearLayout.VERTICAL);
        // The colours are set here, so the dialog leaves the texts alone.
        content.setTag(ArsoundDialog.KEEP_COLORS);

        boolean editable = library.target.editable;
        TextView hint = label(text("Отметьте плейлисты: их треки добавятся в конец плейлиста ниже. "
                        + "Порядок меняется перетаскиванием за правый край строки.",
                "Tick the playlists whose tracks go to the end of the playlist below. "
                        + "Drag a row by its right edge to change the order."), 13, secondary);
        hint.setPadding(dp(24), 0, dp(24), dp(editable ? 10 : 4));
        content.addView(hint);
        if (!editable) {
            TextView note = label(text("Это не ваш плейлист: треки появятся только на этом телефоне, в SoundCloud они не попадут.",
                    "This is not your playlist: the tracks appear on this phone only and do not go to SoundCloud."), 13, accent);
            note.setPadding(dp(24), 0, dp(24), dp(10));
            content.addView(note);
        }
        content.addView(targetRow());
        content.addView(divider());

        scroll = new CappedScroll(context);
        scroll.onViewportChanged = this::loadVisibleCovers;
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        // A row lifted while dragged may reach over the edge of the list.
        list.setClipChildren(false);
        queueTitle = sectionTitle(text("Порядок добавления", "Order of adding"));
        list.addView(queueTitle);
        queue = new ReorderColumn(context, liftColor());
        list.addView(queue);
        poolTitle = sectionTitle(text("Плейлисты и альбомы", "Playlists and albums"));
        list.addView(poolTitle);
        pool = new LinearLayout(context);
        pool.setOrientation(LinearLayout.VERTICAL);
        for (Row row : rows) pool.addView(row.view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list.addView(pool);
        scroll.addView(list);
        scroll.setClipChildren(false);
        content.scroll = scroll;
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        summary = label("", 13, secondary);
        summary.setPadding(dp(24), dp(10), dp(24), 0);
        content.addView(summary);

        dialog = new ArsoundDialog(context)
                .setTitle(text("Совместить плейлисты", "Merge playlists"))
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(text("Совместить", "Merge"), (d, which) -> listener.onMerge(tickedCandidates()))
                .setOnDismissListener((DialogInterface d) -> closed = true)
                .show();
        // The window did not open (its screen is going away): nobody is there to see the covers.
        if (!dialog.isShowing()) closed = true;
        updateState();
    }

    /** The playlist that gets the tracks: it cannot be moved or unticked. */
    private View targetRow() {
        MergeCandidate target = library.target;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ROW_DP));
        row.setPadding(dp(20), dp(6), dp(20), dp(6));
        ImageView cover = coverView();
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(dp(COVER_DP), dp(COVER_DP));
        coverParams.rightMargin = dp(12);
        row.addView(cover, coverParams);
        loadCover(cover, target);
        row.addView(labels(target.title, subtitle(target)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView pill = label(text("Сюда", "Target"), 12, accent);
        pill.setPadding(dp(10), dp(3), dp(10), dp(3));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(100));
        shape.setColor((accent & 0x00FFFFFF) | 0x33000000);
        pill.setBackground(shape);
        row.addView(pill);
        return row;
    }

    private View divider() {
        View line = new View(context);
        line.setBackgroundColor((primary & 0x00FFFFFF) | 0x1F000000);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        params.leftMargin = dp(24);
        params.rightMargin = dp(24);
        line.setLayoutParams(params);
        return line;
    }

    // endregion

    // region State

    private void toggle(Row row) {
        // A row cannot come or go while another one is dragged or settling into its place.
        if (row.moving || queue.isBusy()) return;
        row.moving = true;
        row.setTicked(!row.ticked);
        updateState();
        // The row stays a moment where it is, so the tick is seen before it moves.
        row.view.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (queue.isBusy()) {
                    // A finger took another row meanwhile: wait for the drop.
                    row.view.postDelayed(this, 60);
                    return;
                }
                row.moving = false;
                move(row);
                updateState();
            }
        }, 130);
    }

    private void move(Row row) {
        if (row.ticked) {
            pool.removeView(row.view);
            queue.addRow(row.view, row.handle, row);
            row.view.setAlpha(0f);
            row.view.animate().alpha(1f).setDuration(160).start();
        } else {
            queue.removeRow(row);
            // Back among the others where the library has it. Only rows that really sit in the pool count: another row
            // unticked a moment ago may not have come back yet, so its flag says "in the pool" while its view is not.
            int index = 0;
            for (Row other : rows) {
                if (other == row) break;
                if (other.view.getParent() == pool) index++;
            }
            pool.addView(row.view, Math.min(index, pool.getChildCount()), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** The ticked playlists in the order the user put them in. */
    private List<MergeCandidate> tickedCandidates() {
        List<MergeCandidate> result = new ArrayList<>();
        // A row that was just unticked is still in the column for a moment, one that was just ticked is not there yet.
        for (Object key : queue.keys()) if (((Row) key).ticked) result.add(((Row) key).candidate);
        for (Row row : rows) if (row.ticked && row.moving && !queue.keys().contains(row)) result.add(row.candidate);
        return result;
    }

    private int tickedCount() {
        int count = 0;
        for (Row row : rows) if (row.ticked) count++;
        return count;
    }

    private void updateState() {
        int ticked = tickedCount();
        queueTitle.setVisibility(ticked == 0 ? View.GONE : View.VISIBLE);
        poolTitle.setVisibility(ticked == rows.size() ? View.GONE : View.VISIBLE);

        TextView button = dialog == null ? null : ArsoundDialog.getButton(dialog, DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setEnabled(ticked > 0);
            button.setAlpha(ticked > 0 ? 1f : 0.4f);
        }

        if (ticked == 0) {
            summary.setTextColor(secondary);
            summary.setText(text("Отметьте хотя бы один плейлист", "Tick at least one playlist"));
            return;
        }
        int sum = 0;
        for (Row row : rows) if (row.ticked) sum += row.candidate.count;
        MergeCandidate target = library.target;
        if (!target.editable) {
            summary.setTextColor(secondary);
            summary.setText(text("Выбрано: " + ticked + " · добавится до " + tracks(sum),
                    "Ticked: " + ticked + " · up to " + tracks(sum) + " are added"));
            return;
        }
        // The tracks of a playlist on SoundCloud count against the limit; the ones of imported files do not,
        // so this is an upper estimate.
        int total = Math.max(target.serverCount, 0) + sum;
        boolean over = total > PlaylistMerge.MAX_TRACKS;
        summary.setTextColor(over ? error : secondary);
        summary.setText(over
                ? text("Выбрано: " + ticked + " · всего до " + total + " треков, а в плейлисте их не больше " + PlaylistMerge.MAX_TRACKS
                        + ": лишние не поместятся",
                "Ticked: " + ticked + " · up to " + total + " tracks, a playlist holds " + PlaylistMerge.MAX_TRACKS
                        + " at most: the rest will not fit")
                : text("Выбрано: " + ticked + " · в плейлисте будет до " + total + " из " + PlaylistMerge.MAX_TRACKS,
                "Ticked: " + ticked + " · up to " + total + " of " + PlaylistMerge.MAX_TRACKS + " tracks in the playlist"));
    }

    // endregion

    // region Views

    /** The colour of a row while it is dragged: a little off the card. */
    private int liftColor() {
        return Colors.blend(surface, primary, 0.12f);
    }

    private RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x33000000), null,
                new ColorDrawable(Color.WHITE));
    }

    private TextView label(CharSequence value, float sizeSp, int color) {
        TextView view = new TextView(context);
        int style = Utils.getResourceIdentifier(ResourceType.STYLE, "Body.Secondary");
        if (style != 0) view.setTextAppearance(style);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setText(value);
        return view;
    }

    private TextView sectionTitle(String value) {
        TextView view = label(value, 12, accent);
        view.setAllCaps(true);
        view.setLetterSpacing(0.06f);
        view.setPadding(dp(24), dp(14), dp(24), dp(4));
        return view;
    }

    /** Title and the line under it, one line each. */
    private View labels(String title, String details) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(context);
        int style = Utils.getResourceIdentifier(ResourceType.STYLE, "Body.Primary");
        if (style != 0) titleView.setTextAppearance(style);
        titleView.setTextColor(primary);
        titleView.setText(title);
        titleView.setSingleLine();
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(titleView);
        TextView detailsView = label(details, 12, secondary);
        detailsView.setSingleLine();
        detailsView.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(detailsView);
        return column;
    }

    private ImageView coverView() {
        ImageView view = new ImageView(context);
        view.setBackgroundColor(highlight);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 6 * density);
            }
        });
        view.setClipToOutline(true);
        // Until the picture is there, or when there is none: the playlist icon.
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setPadding(dp(9), dp(9), dp(9), dp(9));
        view.setImageResource(DownloadTrackPatch.arsoundIcon("ic_actions_playlist"));
        view.setColorFilter(secondary);
        return view;
    }

    private void loadCover(ImageView view, MergeCandidate candidate) {
        String address = candidate.cover;
        if (address == null || address.isEmpty()) return;
        Bitmap cached = THUMBNAILS.get(address);
        if (cached != null) {
            showCover(view, cached);
            return;
        }
        COVERS.execute(() -> {
            // The window was closed while this waited for a thread: nobody is there to show the picture to.
            if (closed) return;
            Bitmap bitmap = LocalCovers.thumbnail(address, dp(COVER_DP));
            if (bitmap == null) return;
            THUMBNAILS.put(address, bitmap);
            if (!closed) Utils.runOnMainThread(() -> showCover(view, bitmap));
        });
    }

    /** Asks for the covers of the rows in the visible part of the list and a little around it. */
    private void loadVisibleCovers() {
        if (closed) return;
        for (Row row : rows) row.loadCoverIfNear();
    }

    private boolean isNearViewport(View row) {
        if (scroll == null || scroll.getHeight() == 0 || row.getHeight() == 0 || !(row.getParent() instanceof View)) return false;
        // The pool and the column of ticked rows are both laid out from the top of the scrolling list.
        int top = row.getTop() + ((View) row.getParent()).getTop();
        int margin = scroll.getHeight() / 2;
        return top + row.getHeight() > scroll.getScrollY() - margin && top < scroll.getScrollY() + scroll.getHeight() + margin;
    }

    private void showCover(ImageView view, Bitmap bitmap) {
        view.clearColorFilter();
        view.setPadding(0, 0, 0, 0);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImageBitmap(bitmap);
    }

    private int dp(float value) {
        return Dimens.dp(context, value);
    }

    // endregion
}
