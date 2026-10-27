package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The thumbnails of the Wallhaven browser for a {@link android.widget.GridView}: the cells, the thumbnails loaded on
 * a few threads, and a cache of the ones in memory.
 * <p>
 * A thumbnail is 300 pixels on its long side, so it is kept as a 16-bit bitmap in a cache of a fixed size and its
 * cell asks again when it comes back into view; cells that were scrolled past before their turn do not load at all.
 */
public final class WallhavenGrid extends BaseAdapter {
    private static final int PLACEHOLDERS = 4;

    private final Context context;
    private final List<Wallhaven.Item> items = new ArrayList<>();
    private final Thumbnails thumbnails = new Thumbnails();
    private final int placeholderColor;
    /** Height of a cell as a part of its width. */
    private float ratio = 4f / 3f;
    private boolean loadingMore;

    public WallhavenGrid(Context context) {
        this.context = context;
        placeholderColor = ArsoundTheme.palette(context, "highlight");
    }

    public void setRatio(float heightPerWidth) {
        ratio = heightPerWidth;
        notifyDataSetChanged();
    }

    public void clear() {
        items.clear();
        loadingMore = false;
        thumbnails.forgetFailures();
        notifyDataSetChanged();
    }

    public void add(List<Wallhaven.Item> more) {
        // The service can repeat a picture on the next page when the list changes meanwhile.
        Set<String> known = new HashSet<>();
        for (Wallhaven.Item item : items) known.add(item.id);
        for (Wallhaven.Item item : more) if (known.add(item.id)) items.add(item);
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

    public Wallhaven.Item item(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    /** The thumbnail of a picture if it is in memory, else null. */
    public Bitmap thumbnail(Wallhaven.Item item) {
        return thumbnails.cached(item.thumbnail);
    }

    @Override
    public int getCount() {
        return items.size() + (loadingMore ? PLACEHOLDERS : 0);
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
        Cell cell = convertView instanceof Cell ? (Cell) convertView : new Cell(context, placeholderColor);
        cell.ratio = ratio;
        cell.show(item(position), thumbnails);
        return cell;
    }

    /** One cell: the thumbnail over a placeholder colour, with a small mark on pictures that are not safe. */
    private static final class Cell extends FrameLayout {
        float ratio = 4f / 3f;
        private final ImageView picture;
        private final TextView mark;

        Cell(Context context, int placeholderColor) {
            super(context);
            GradientDrawable base = new GradientDrawable();
            base.setColor(placeholderColor);
            setBackground(base);
            float radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14, getResources().getDisplayMetrics());
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            setClipToOutline(true);
            setForeground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));

            picture = new ImageView(context);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(picture, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            mark = new TextView(context);
            mark.setText("18+");
            mark.setTextColor(0xFFFFFFFF);
            mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            mark.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(0xB3000000);
            pill.setCornerRadius(radius);
            mark.setBackground(pill);
            int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6, getResources().getDisplayMetrics()));
            mark.setPadding(pad, pad / 3, pad, pad / 3);
            LayoutParams markParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            markParams.setMargins(pad, pad, pad, pad);
            addView(mark, markParams);
        }

        /** @param item The picture, or null for an empty cell. */
        void show(Wallhaven.Item item, Thumbnails thumbnails) {
            if (item == null) {
                picture.setTag(null);
                picture.setImageDrawable(null);
                mark.setVisibility(GONE);
                setContentDescription(null);
                return;
            }
            mark.setVisibility(item.isSafe() ? GONE : VISIBLE);
            setContentDescription(item.resolution);
            Bitmap cached = thumbnails.cached(item.thumbnail);
            picture.setTag(item.thumbnail);
            picture.animate().cancel();
            picture.setAlpha(1f);
            if (cached != null) {
                picture.setImageBitmap(cached);
            } else {
                picture.setImageDrawable(null);
                thumbnails.load(item.thumbnail, picture);
            }
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(width * ratio), MeasureSpec.EXACTLY));
        }
    }

    /** Loads thumbnails on three threads and keeps the newest ones in a cache of bounded size. */
    private static final class Thumbnails {
        private final LruCache<String, Bitmap> cache;
        private final ExecutorService pool;
        /** Thumbnails that could not be read, so scrolling does not ask for them over and over. */
        private final Set<String> failed = Collections.synchronizedSet(new HashSet<>());

        Thumbnails() {
            int bytes = (int) Math.min(24L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16);
            cache = new LruCache<String, Bitmap>(bytes) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };
            ThreadFactory factory = task -> {
                Thread thread = new Thread(task, "arsound-wallhaven-thumbnail");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            };
            pool = Executors.newFixedThreadPool(3, factory);
        }

        Bitmap cached(String url) {
            return cache.get(url);
        }

        void forgetFailures() {
            failed.clear();
        }

        /** Loads the thumbnail into the view, if the view still wants it when its turn comes and when it is there. */
        void load(String url, ImageView view) {
            if (failed.contains(url)) return;
            pool.execute(() -> {
                // The cell was reused for another picture while this one waited.
                if (!url.equals(view.getTag())) return;
                Bitmap bitmap = cache.get(url);
                if (bitmap == null) {
                    try {
                        byte[] bytes = Wallhaven.readThumbnail(url);
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inPreferredConfig = Bitmap.Config.RGB_565;
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    } catch (Exception | OutOfMemoryError ex) {
                        Logger.printInfo(() -> "Wallhaven: a thumbnail did not load: " + ex.getClass().getSimpleName());
                    }
                    if (bitmap == null) {
                        failed.add(url);
                        return;
                    }
                    cache.put(url, bitmap);
                }
                Bitmap ready = bitmap;
                Utils.runOnMainThread(() -> {
                    if (!url.equals(view.getTag())) return;
                    view.setImageBitmap(ready);
                    view.setAlpha(0f);
                    view.animate().alpha(1f).setDuration(160).start();
                });
            });
        }
    }
}
