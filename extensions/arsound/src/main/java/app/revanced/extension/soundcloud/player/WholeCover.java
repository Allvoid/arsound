package app.revanced.extension.soundcloud.player;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.BoolSettings;
import app.revanced.extension.soundcloud.shared.Dimens;

/**
 * The whole cover in the open player. SoundCloud stretches the square cover over the full height of the page, so only
 * a strip of it as wide as the screen is seen, and slides that strip sideways as the track plays. Here the cover stands
 * whole between the title and the waveform, with rounded corners, and the page behind it is filled with a blurred,
 * darkened copy of it ({@link CoverBackdrop}).
 * <p>
 * SoundCloud's own image views stay the cover: they keep loading the picture, fading in the sharper one, the tap that
 * plays and pauses, the shrink on pause and the corners of the slide. Only their size and place change, and the
 * sideways slide is not set up. The setting is read when a page lays its artwork out; the switch restarts the app.
 * Main thread.
 */
public final class WholeCover {
    /** The space between the cover and the edges of the screen. */
    private static final float SIDE_DP = 24;
    /** The space between the cover and the title above it or the waveform below it. */
    private static final float GAP_DP = 16;
    /** The rounding of the cover's corners. */
    private static final float CORNER_DP = 10;
    /** A cover smaller than this would look lost: below it the cover takes the width and lets the waveform overlap. */
    private static final float MIN_SIDE_DP = 160;

    private static int holderId = -1, imageId, overlayId, headerId, timestampId;

    private WholeCover() {
    }

    public static boolean isEnabled() {
        return BoolSettings.WHOLE_PLAYER_COVER.get();
    }

    /**
     * Injection point, the start of the artwork controller's "width changed", which sets up the sideways slide of the
     * cover. Returns true when the cover is shown whole: the slide is left out.
     */
    public static boolean skipSlide(View artwork) {
        try {
            if (!isEnabled() || !(artwork instanceof FrameLayout)) return false;
            loadIds();
            // Kept on the view and not in a map: the page holds the view, so a map would hold both for good.
            Page page = (Page) artwork.getTag(holderId);
            if (page == null) {
                page = Page.create((FrameLayout) artwork);
                if (page == null) return false;
                artwork.setTag(holderId, page);
            }
            page.place();
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Whole cover: artwork not set up", ex);
            return false;
        }
    }

    /**
     * Injection point, right after the artwork view took its new size. SoundCloud's "width changed" reaches
     * {@link #skipSlide} through a listener the view forgets when it leaves the window, and nothing sets it again. A
     * page taken off the screen before its first layout then never heard of its size and kept the stretched cover.
     *
     * @param listener The view's "width changed" listener; null when it is gone.
     */
    public static void onArtworkSized(View artwork, Object listener) {
        if (listener == null && skipSlide(artwork)) {
            Logger.printInfo(() -> "Whole cover: a page without the width listener set up by its size");
        }
    }

    private static void loadIds() {
        if (holderId >= 0) return;
        holderId = id("artwork_holder");
        imageId = id("artwork_image_view");
        overlayId = id("artwork_overlay_image");
        headerId = id("track_page_header");
        timestampId = id("timestamp_holder");
    }

    private static int id(String name) {
        return Utils.getResourceIdentifier(ResourceType.ID, name);
    }

    /** The cover of one player page and the picture behind it. */
    private static final class Page implements View.OnLayoutChangeListener, ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        private final FrameLayout artwork;
        private final View holder;
        private final ImageView image;
        private final ImageView overlay;
        private final ImageView backdrop;
        /** The picture the backdrop was made from, so a new one is seen. */
        private WeakReference<Drawable> source = new WeakReference<>(null);
        /** Counts the pictures asked for, so a late one does not replace a newer one. */
        private int generation;
        private boolean predrawing;

        static Page create(FrameLayout artwork) {
            loadIds();
            View holder = artwork.findViewById(holderId);
            View image = artwork.findViewById(imageId);
            View overlay = artwork.findViewById(overlayId);
            if (!(holder instanceof FrameLayout) || !(image instanceof ImageView)) {
                Logger.printInfo(() -> "Whole cover: the artwork has no image view, SoundCloud's look stays");
                return null;
            }
            return new Page(artwork, holder, (ImageView) image, overlay instanceof ImageView ? (ImageView) overlay : null);
        }

        private Page(FrameLayout artwork, View holder, ImageView image, ImageView overlay) {
            this.artwork = artwork;
            this.holder = holder;
            this.image = image;
            this.overlay = overlay;
            backdrop = new ImageView(artwork.getContext());
            backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
            backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            artwork.addView(backdrop, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            float corner = Dimens.dpExact(artwork.getContext(), CORNER_DP);
            ViewOutlineProvider rounded = new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
                }
            };
            for (ImageView view : covers()) {
                view.setOutlineProvider(rounded);
                view.setClipToOutline(true);
            }
            holder.setTranslationX(0);

            if (artwork.getParent() instanceof View) ((View) artwork.getParent()).addOnLayoutChangeListener(this);
            artwork.addOnAttachStateChangeListener(this);
            if (artwork.isAttachedToWindow()) onViewAttachedToWindow(artwork);
        }

        private ImageView[] covers() {
            return overlay == null ? new ImageView[]{image} : new ImageView[]{image, overlay};
        }

        /**
         * Sizes and places the cover: as wide as the screen less its margins, no taller than the room between the
         * title block and the waveform, in the middle of that room. The page's own views are measured after each of
         * its layouts; the cover's place is set only when it changes, so this does not feed back into the layout.
         */
        void place() {
            int width = artwork.getWidth();
            if (width == 0 || artwork.getHeight() == 0 || !(artwork.getParent() instanceof ViewGroup)) return;
            ViewGroup page = (ViewGroup) artwork.getParent();
            int side = Dimens.dp(artwork.getContext(), SIDE_DP);
            int gap = Dimens.dp(artwork.getContext(), GAP_DP);

            View header = page.findViewById(headerId);
            int top = header != null && header.getVisibility() != View.GONE && header.getHeight() > 0
                    ? top(page, header) + header.getHeight() + gap : artwork.getTop() + Dimens.dp(artwork.getContext(), 160);
            View timestamp = page.findViewById(timestampId);
            int bottom = timestamp != null && timestamp.getHeight() > 0
                    ? top(page, timestamp) : artwork.getBottom() - Dimens.dp(artwork.getContext(), 120);
            // The holder of the timestamps spans the waveform: its top is the top of the bars.
            bottom -= gap;

            int size = Math.min(width - 2 * side, bottom - top);
            if (size < Dimens.dp(artwork.getContext(), MIN_SIDE_DP)) size = width - 2 * side;
            if (size <= 0) return;
            int left = (width - size) / 2;
            int y = Math.max(0, top + Math.max(0, bottom - top - size) / 2 - artwork.getTop());

            holder.setTranslationX(0);
            for (ImageView view : covers()) {
                ViewGroup.LayoutParams params = view.getLayoutParams();
                if (!(params instanceof FrameLayout.LayoutParams)) continue;
                FrameLayout.LayoutParams frame = (FrameLayout.LayoutParams) params;
                if (frame.width == size && frame.height == size && frame.leftMargin == left && frame.topMargin == y) continue;
                frame.width = size;
                frame.height = size;
                frame.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                frame.leftMargin = left;
                frame.topMargin = y;
                frame.rightMargin = 0;
                frame.bottomMargin = 0;
                view.setLayoutParams(frame);
            }
        }

        /** The top of a view inside the page, in the page's coordinates, through the views between them. */
        private static int top(ViewGroup page, View view) {
            Rect rect = new Rect(0, 0, view.getWidth(), view.getHeight());
            try {
                page.offsetDescendantRectToMyCoords(view, rect);
            } catch (IllegalArgumentException ex) {
                return view.getTop();
            }
            return rect.top;
        }

        @Override
        public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            try {
                place();
            } catch (Exception ex) {
                Logger.printException(() -> "Whole cover: not placed", ex);
            }
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            ViewTreeObserver observer = artwork.getViewTreeObserver();
            if (predrawing || !observer.isAlive()) return;
            observer.addOnPreDrawListener(this);
            predrawing = true;
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            if (!predrawing) return;
            ViewTreeObserver observer = artwork.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(this);
            predrawing = false;
        }

        /** Before each frame: a new picture in the cover gets a new backdrop. Only a comparison when nothing changed. */
        @Override
        public boolean onPreDraw() {
            try {
                Drawable cover = picture();
                if (cover != source.get()) {
                    source = new WeakReference<>(cover);
                    makeBackdrop(cover);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Whole cover: backdrop not made", ex);
            }
            return true;
        }

        /**
         * The cover's picture: the sharper one on top once it is there, else the first one; the top layer of a fade
         * between two pictures.
         */
        private Drawable picture() {
            Drawable drawable = overlay != null && overlay.getDrawable() != null ? overlay.getDrawable() : image.getDrawable();
            while (drawable instanceof LayerDrawable) {
                LayerDrawable layers = (LayerDrawable) drawable;
                if (layers.getNumberOfLayers() == 0) return null;
                drawable = layers.getDrawable(layers.getNumberOfLayers() - 1);
            }
            return drawable;
        }

        private void makeBackdrop(Drawable cover) {
            int mine = ++generation;
            if (cover == null) {
                backdrop.setImageDrawable(null);
                return;
            }
            Bitmap bitmap = cover instanceof BitmapDrawable ? ((BitmapDrawable) cover).getBitmap() : null;
            if (bitmap != null && !bitmap.isRecycled()) {
                // A hardware bitmap is copied back from the graphics memory to be read: off the main thread.
                Utils.runOnBackgroundThread(() -> {
                    Bitmap small = shrink(bitmap);
                    Utils.runOnMainThread(() -> show(mine, small));
                });
                return;
            }
            // A drawn placeholder: small enough to draw here.
            Bitmap small = Bitmap.createBitmap(CoverBackdrop.SIDE, CoverBackdrop.SIDE, Bitmap.Config.ARGB_8888);
            Rect bounds = cover.getBounds();
            if (bounds.isEmpty()) {
                source = new WeakReference<>(null);
                return;
            }
            Canvas canvas = new Canvas(small);
            canvas.scale(CoverBackdrop.SIDE / (float) bounds.width(), CoverBackdrop.SIDE / (float) bounds.height());
            canvas.translate(-bounds.left, -bounds.top);
            try {
                cover.draw(canvas);
            } catch (IllegalArgumentException ex) {
                // A hardware bitmap inside a drawable of its own kind cannot be drawn on the processor.
                Logger.printDebug(() -> "Whole cover: the picture cannot be read: " + cover.getClass().getName());
                small.recycle();
                return;
            }
            show(mine, finish(small));
        }

        private void show(int mine, Bitmap small) {
            if (mine != generation || small == null) return;
            backdrop.setImageBitmap(small);
        }

        /** The picture shrunk to the backdrop's size, blurred and darkened; null when it cannot be read. */
        private static Bitmap shrink(Bitmap bitmap) {
            try {
                Bitmap readable = bitmap;
                if (Build.VERSION.SDK_INT >= 26 && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                    readable = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    if (readable == null) return null;
                }
                // Halved in two steps, so the small picture averages the cover and does not pick single pixels.
                int middle = Math.max(CoverBackdrop.SIDE, Math.min(readable.getWidth(), CoverBackdrop.SIDE * 4));
                Bitmap step = Bitmap.createScaledBitmap(readable, middle, middle, true);
                Bitmap small = Bitmap.createScaledBitmap(step, CoverBackdrop.SIDE, CoverBackdrop.SIDE, true);
                if (step != readable && step != small) step.recycle();
                if (readable != bitmap && readable != small) readable.recycle();
                if (!small.isMutable()) {
                    Bitmap mutable = small.copy(Bitmap.Config.ARGB_8888, true);
                    small.recycle();
                    small = mutable;
                }
                return finish(small);
            } catch (Exception ex) {
                Logger.printException(() -> "Whole cover: picture not shrunk", ex);
                return null;
            }
        }

        private static Bitmap finish(Bitmap small) {
            int side = CoverBackdrop.SIDE;
            int[] pixels = new int[side * side];
            small.getPixels(pixels, 0, side, 0, 0, side, side);
            small.setPixels(CoverBackdrop.process(pixels, side), 0, side, 0, 0, side, side);
            return small;
        }
    }
}
