package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The results of the wallpaper catalogue for a {@link android.widget.GridView}: the list of pictures (of the source the
 * screen shows, {@link WallpaperSource}) and a {@link WallhavenCell} for each. Nothing here depends on which source the
 * pictures are from; an item carries its own source. The pictures are loaded and kept by a {@link ThumbnailLoader}: thumbnails first, and
 * once scrolling has settled {@link #sharpenVisible} asks for the sharp version of the cells in view.
 * <p>
 * The screen tells the grid how many columns it has and how big a cell is in pixels ({@link #setLayout}); the sharp
 * pictures are made at exactly that size.
 * <p>
 * A search for phone-shaped pictures goes on with wide ones when the tall ones run out ({@link Wallhaven.Query#widened}),
 * so wide and tall pictures meet in the same tall cells: both are cut to their middle (the cell does it, the sharp
 * picture does it by {@link CoverCrop}) and the cell marks the wide ones. Tall pages come first and wide ones after them,
 * so a picture is never listed twice; {@link #add} drops a repeat anyway.
 */
public final class WallhavenGrid extends BaseAdapter {
    /** Rows of skeleton cells shown while the next page is on its way. */
    private static final int PLACEHOLDER_ROWS = 2;

    private final Context context;
    private final List<WallpaperItem> items = new ArrayList<>();
    private final ThumbnailLoader thumbnails;
    private final int placeholderColor;
    /** Height of a cell as a part of its width. */
    private float ratio = 16f / 9f;
    private int columns = 3;
    /** The size of a cell in pixels, 0 until the screen has laid the grid out. */
    private int cellWidth, cellHeight;
    private boolean loadingMore;

    public WallhavenGrid(Context context) {
        this.context = context;
        thumbnails = new ThumbnailLoader(context);
        placeholderColor = ArsoundTheme.palette(context, "highlight");
    }

    public void setRatio(float heightPerWidth) {
        ratio = heightPerWidth;
        cellHeight = Math.round(cellWidth * ratio);
        notifyDataSetChanged();
    }

    /**
     * Tells the grid how it is laid out.
     *
     * @param columns The number of columns.
     * @param width   The width of a cell in pixels.
     */
    public void setLayout(int columns, int width) {
        int height = Math.round(width * ratio);
        if (this.columns == columns && cellWidth == width && cellHeight == height) return;
        this.columns = Math.max(1, columns);
        cellWidth = width;
        cellHeight = height;
        notifyDataSetChanged();
    }

    /** Asks for the sharp picture of every cell in view. Call it when scrolling has been still for a moment. */
    public void sharpenVisible(GridView view) {
        for (int i = 0; i < view.getChildCount(); i++) {
            View child = view.getChildAt(i);
            if (child instanceof WallhavenCell) ((WallhavenCell) child).sharpen(thumbnails);
        }
    }

    public void clear() {
        items.clear();
        loadingMore = false;
        thumbnails.forgetFailures();
        notifyDataSetChanged();
    }

    public void add(List<WallpaperItem> more) {
        // The service can repeat a picture on the next page when the list changes meanwhile (Commons: a random order does).
        Set<String> known = new HashSet<>();
        for (WallpaperItem item : items) known.add(item.id);
        for (WallpaperItem item : more) if (known.add(item.id)) items.add(item);
        notifyDataSetChanged();
    }

    /** Shows empty cells at the end of the list while the next page is on its way. */
    public void setLoadingMore(boolean loading) {
        if (loadingMore == loading) return;
        loadingMore = loading;
        notifyDataSetChanged();
    }

    public int size() {
        return items.size();
    }

    public WallpaperItem item(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    /** The screen is gone: stops loading thumbnails and lets go of the threads. */
    public void close() {
        thumbnails.close();
    }

    /** The thumbnail of a picture if it is in memory, else null. */
    public Bitmap thumbnail(WallpaperItem item) {
        return thumbnails.cached(item.thumbnail);
    }

    @Override
    public int getCount() {
        return items.size() + (loadingMore ? columns * PLACEHOLDER_ROWS : 0);
    }

    @Override
    public Object getItem(int position) {
        return item(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean isEnabled(int position) {
        return position < items.size();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        WallhavenCell cell = convertView instanceof WallhavenCell
                ? (WallhavenCell) convertView : new WallhavenCell(context, placeholderColor);
        cell.ratio = ratio;
        cell.show(item(position), thumbnails, cellWidth, cellHeight);
        return cell;
    }
}
