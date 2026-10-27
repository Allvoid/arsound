package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.local.PlaylistMerge.text;
import static app.revanced.extension.soundcloud.local.PlaylistMerge.tracks;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
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
        void onMerge(List<PlaylistMerge.Candidate> sources);
    }

    private static final int ROW_DP = 56;
    private static final int COVER_DP = 40;
    /** The covers of the last lists, so a dialog opened again does not load them again. */
    private static final LruCache<String, Bitmap> THUMBNAILS = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
        @Override
        protected int sizeOf(String address, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    private final Context context;
    private final PlaylistMerge.Library library;
    private final Listener listener;
    private final float density;
    private final int accent, primary, secondary, highlight, error, surface;
    private final List<Row> rows = new ArrayList<>();
    private final ExecutorService covers = Executors.newFixedThreadPool(3, task -> {
        Thread thread = new Thread(task, "arsound-merge-covers");
        thread.setDaemon(true);
        return thread;
    });

    private Dialog dialog;
    private ReorderColumn queue;
    private LinearLayout pool;
    private TextView queueTitle, poolTitle, summary;

    MergePicker(Context context, PlaylistMerge.Library library, Listener listener) {
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
        final PlaylistMerge.Candidate candidate;
        final LinearLayout view;
        final Box box;
        final View handle;
        final ImageView cover;
        boolean ticked;
        /** The tick is shown before the row moves, so a tap is seen to land. */
        boolean moving;

        Row(PlaylistMerge.Candidate candidate) {
            this.candidate = candidate;
            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setMinimumHeight(dp(ROW_DP));
            view.setPadding(dp(20), dp(6), dp(4), dp(6));
            view.setBackground(ripple());
            view.setOnClickListener(v -> toggle(this));

            box = new Box(context, accent, secondary);
            LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(dp(20), dp(20));
            boxParams.rightMargin = dp(14);
            view.addView(box, boxParams);

            cover = coverView();
            LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(dp(COVER_DP), dp(COVER_DP));
            coverParams.rightMargin = dp(12);
            view.addView(cover, coverParams);
            loadCover(cover, candidate);

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
    }

    private String subtitle(PlaylistMerge.Candidate candidate) {
        String count = candidate.count < 0 ? "" : tracks(candidate.count);
        return candidate.album ? text("Альбом", "Album") + (count.isEmpty() ? "" : " · " + count) : count;
    }

    // region Window

    void show() {
        for (PlaylistMerge.Candidate candidate : library.sources) rows.add(new Row(candidate));

        Content content = new Content(context);
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

        CappedScroll scroll = new CappedScroll(context);
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
                .setOnDismissListener((DialogInterface d) -> covers.shutdownNow())
                .show();
        updateState();
    }

    /** The playlist that gets the tracks: it cannot be moved or unticked. */
    private View targetRow() {
        PlaylistMerge.Candidate target = library.target;
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
        if (row.moving) return;
        row.moving = true;
        row.setTicked(!row.ticked);
        updateState();
        // The row stays a moment where it is, so the tick is seen before it moves.
        row.view.postDelayed(() -> {
            row.moving = false;
            move(row);
            updateState();
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
            // Back among the others where the library has it.
            int index = 0;
            for (Row other : rows) {
                if (other == row) break;
                if (!other.ticked) index++;
            }
            pool.addView(row.view, index, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** The ticked playlists in the order the user put them in. */
    private List<PlaylistMerge.Candidate> tickedCandidates() {
        List<PlaylistMerge.Candidate> result = new ArrayList<>();
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
        PlaylistMerge.Candidate target = library.target;
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
        return Color.argb(255,
                blend(Color.red(surface), Color.red(primary)),
                blend(Color.green(surface), Color.green(primary)),
                blend(Color.blue(surface), Color.blue(primary)));
    }

    private static int blend(int from, int to) {
        return Math.round(from + (to - from) * 0.12f);
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
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        column.addView(titleView);
        TextView detailsView = label(details, 12, secondary);
        detailsView.setSingleLine();
        detailsView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        column.addView(detailsView);
        return column;
    }

    private ImageView coverView() {
        ImageView view = new ImageView(context);
        view.setBackgroundColor(highlight);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline outline) {
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

    private void loadCover(ImageView view, PlaylistMerge.Candidate candidate) {
        String address = candidate.cover;
        if (address == null || address.isEmpty()) return;
        Bitmap cached = THUMBNAILS.get(address);
        if (cached != null) {
            showCover(view, cached);
            return;
        }
        try {
            covers.execute(() -> {
                Bitmap bitmap = LocalCovers.thumbnail(address, dp(COVER_DP));
                if (bitmap == null) return;
                THUMBNAILS.put(address, bitmap);
                Utils.runOnMainThread(() -> showCover(view, bitmap));
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            // The dialog was closed: there is nobody to show the picture to.
        }
    }

    private void showCover(ImageView view, Bitmap bitmap) {
        view.clearColorFilter();
        view.setPadding(0, 0, 0, 0);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImageBitmap(bitmap);
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    // endregion

    /** The whole content of the window: fits the list to the height the dialog allows, below the parts that stay in view. */
    private static final class Content extends LinearLayout {
        CappedScroll scroll;

        Content(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            if (scroll != null) {
                int fixed = 0;
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (child == scroll || child.getVisibility() == GONE) continue;
                    measureChild(child, widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    fixed += child.getMeasuredHeight();
                }
                int room = ArsoundDialog.bodyMaxHeight(getContext()) - fixed;
                scroll.limit = Math.max(Math.round(120 * getResources().getDisplayMetrics().density), room);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /** A list that scrolls inside the dialog's own scrolling body, and keeps the touches that are meant for it. */
    private static final class CappedScroll extends ScrollView {
        int limit = Integer.MAX_VALUE;

        CappedScroll(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            // The body of the dialog scrolls too, when the window is very low: this list goes first.
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            return super.dispatchTouchEvent(event);
        }
    }

    /** A tick box in the colours of the theme. */
    private static final class Box extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tick = new Path();
        private final int on, off;
        private boolean checked;

        Box(Context context, int on, int off) {
            super(context);
            this.on = on;
            this.off = off;
        }

        void setChecked(boolean checked) {
            this.checked = checked;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float stroke = 2 * getResources().getDisplayMetrics().density;
            float radius = 5 * getResources().getDisplayMetrics().density;
            RectF box = new RectF(stroke / 2, stroke / 2, getWidth() - stroke / 2, getHeight() - stroke / 2);
            if (checked) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(on);
                canvas.drawRoundRect(box, radius, radius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(ArsoundDialog.contrast(on));
                tick.reset();
                tick.moveTo(getWidth() * 0.26f, getHeight() * 0.52f);
                tick.lineTo(getWidth() * 0.43f, getHeight() * 0.69f);
                tick.lineTo(getWidth() * 0.74f, getHeight() * 0.33f);
                canvas.drawPath(tick, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setColor(off);
                canvas.drawRoundRect(box, radius, radius, paint);
            }
        }
    }
}
