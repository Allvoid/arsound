package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.shared.Lang;

/**
 * One cell of the catalogue grid (Wallhaven or Commons): the picture over a shimmering skeleton, with a small mark on
 * pictures that are not safe and another on a wide picture in a tall cell (only its middle is shown, as on the phone's
 * screen). {@link WallhavenGrid} makes the cells and tells them their size; the pictures come from a
 * {@link ThumbnailLoader}.
 */
final class WallhavenCell extends FrameLayout {
    /** Height of the cell as a part of its width. */
    float ratio = 4f / 3f;
    private final ImageView picture;
    private final TextView mark;
    /** "wide", bottom left: the picture is cut to the cell's middle, which the phone will do too. */
    private final TextView wideMark;
    private final ShimmerDrawable shimmer;
    /** The still mark of a picture that did not load. */
    private final TextView failedMark;
    /** The picture shown, or null for an empty cell. */
    private Wallhaven.Item item;
    /** The size the grid says the cell has, in pixels; 0 until the grid is laid out. */
    private int cellWidth, cellHeight;

    WallhavenCell(Context context, int placeholderColor) {
        super(context);
        shimmer = new ShimmerDrawable(placeholderColor);
        setBackgroundColor(placeholderColor);
        float radius = Dimens.dpExact(getContext(), 14);
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
        int pad = Dimens.dp(getContext(), 6);
        mark.setPadding(pad, pad / 3, pad, pad / 3);
        LayoutParams markParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        markParams.setMargins(pad, pad, pad, pad);
        addView(mark, markParams);

        wideMark = new TextView(context);
        wideMark.setText(Lang.text("широкая", "wide"));
        wideMark.setTextColor(0xFFFFFFFF);
        wideMark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        wideMark.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable widePill = new GradientDrawable();
        widePill.setColor(0xB3000000);
        widePill.setCornerRadius(radius);
        wideMark.setBackground(widePill);
        wideMark.setPadding(pad, pad / 3, pad, pad / 3);
        wideMark.setVisibility(GONE);
        LayoutParams wideParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        wideParams.setMargins(pad, pad, pad, pad);
        addView(wideMark, wideParams);

        failedMark = new TextView(context);
        failedMark.setText("!");
        failedMark.setTextColor(0x99FFFFFF);
        failedMark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        failedMark.setTypeface(Typeface.DEFAULT_BOLD);
        failedMark.setVisibility(GONE);
        addView(failedMark, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
    }

    /** The picture did not load: the shimmer stops, and a still mark shows until it is asked again. */
    void showFailed() {
        showSkeleton(false);
        failedMark.setVisibility(VISIBLE);
    }

    /** The picture is asked again: the shimmer comes back. */
    void showLoading() {
        failedMark.setVisibility(GONE);
        showSkeleton(true);
    }

    /**
     * @param item   The picture, or null for an empty cell.
     * @param width  The size of the cell in pixels as the grid works it out, or 0 when not known yet.
     */
    void show(Wallhaven.Item item, ThumbnailLoader thumbnails, int width, int height) {
        this.item = item;
        cellWidth = width;
        cellHeight = height;
        picture.animate().cancel();
        picture.setAlpha(1f);
        failedMark.setVisibility(GONE);
        if (item == null) {
            picture.setTag(null);
            picture.setImageDrawable(null);
            showSkeleton(true);
            mark.setVisibility(GONE);
            wideMark.setVisibility(GONE);
            setContentDescription(null);
            return;
        }
        mark.setVisibility(item.isSafe() ? GONE : VISIBLE);
        // Only in a tall cell: in a cell of the wide shape the picture is shown whole enough and needs no word.
        wideMark.setVisibility(ratio > 1f && item.isWide() ? VISIBLE : GONE);
        setContentDescription(item.label());
        Bitmap cached = thumbnails.cachedSharp(item, width, height);
        if (cached == null) cached = thumbnails.cached(item.thumbnail);
        picture.setTag(item.thumbnail);
        if (cached != null) {
            picture.setImageBitmap(cached);
            showSkeleton(false);
        } else {
            picture.setImageDrawable(null);
            showSkeleton(true);
            thumbnails.load(item, picture, width, height);
        }
    }

    /** Makes the picture sharp if the cell stayed in view; called by the grid when scrolling has settled. */
    void sharpen(ThumbnailLoader thumbnails) {
        if (item != null && cellWidth > 0 && cellHeight > 0 && picture.getDrawable() != null) {
            thumbnails.sharpen(item, picture, cellWidth, cellHeight);
        }
    }

    private void showSkeleton(boolean on) {
        if (on) {
            picture.setBackground(shimmer);
            if (isAttachedToWindow()) shimmer.start();
        } else {
            shimmer.stop();
            picture.setBackground(null);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (picture.getBackground() == shimmer) shimmer.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        shimmer.stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(width * ratio), MeasureSpec.EXACTLY));
    }
}
