package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.BoolSettings;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The round button of the full-screen player that switches {@link SlowedPlayback} on and off. It stands in the top
 * row, left of the button that closes the player (between the title and that button), looks like the buttons of the
 * column on the right and follows the top bar: it is as visible as the close button is.
 * <p>
 * It is made in code and put on the page of the track, not into the layout of the player: the column of buttons is a
 * layout that is as wide as its widest button and keeps the close button in the middle of it, so a button added to the
 * left would push the close button away from the edge.
 */
public final class SlowedButton {
    private static final int FALLBACK_ACCENT = 0xFFFF5500;
    private static final int GAP_DP = 8;

    private SlowedButton() {
    }

    /** Called when a player page is filled with a track: the page gets its button once. */
    public static void attach(View page) {
        try {
            if (!(page instanceof RelativeLayout) || page.getTag(R_TAG) != null) return;
            int closeId = id("player_close_indicator");
            View close = closeId == 0 ? null : page.findViewById(closeId);
            int icon = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_slowed");
            if (!(close instanceof ImageButton) || !(close.getParent() instanceof View) || icon == 0) return;
            Follower follower = new Follower((RelativeLayout) page, (ImageButton) close, icon);
            page.setTag(R_TAG, follower);
        } catch (Throwable ex) {
            Logger.printException(() -> "Slowed button: could not add it to the player page", ex);
        }
    }

    private static final int R_TAG = 0x41534c57;

    private static int id(String name) {
        return Utils.getResourceIdentifier(ResourceType.ID, name);
    }

    /** The button of one page and what keeps it in its place and in step with the top bar. */
    private static final class Follower implements ViewTreeObserver.OnPreDrawListener, View.OnLayoutChangeListener,
            View.OnAttachStateChangeListener {
        private final RelativeLayout page;
        private final ImageButton close;
        private final ImageButton button;
        private final Rect rect = new Rect();
        private ColorStateList offTint;
        private Boolean shownOn;

        Follower(RelativeLayout page, ImageButton close, int icon) {
            this.page = page;
            this.close = close;
            Context context = page.getContext();
            button = new ImageButton(context);
            // Like the button next to it: its round glass, its padding, the way the picture is fitted.
            Drawable.ConstantState glass = close.getBackground() == null ? null : close.getBackground().getConstantState();
            if (glass != null) button.setBackground(glass.newDrawable(close.getResources()));
            button.setPadding(close.getPaddingLeft(), close.getPaddingTop(), close.getPaddingRight(), close.getPaddingBottom());
            button.setScaleType(close.getScaleType());
            button.setImageResource(icon);
            offTint = close.getImageTintList() != null ? close.getImageTintList() : ColorStateList.valueOf(0xFFFFFFFF);
            button.setImageTintList(offTint);
            button.setVisibility(View.INVISIBLE);
            button.setOnClickListener(v -> toggle(context));
            int size = Math.max(close.getWidth(), close.getHeight());
            if (size <= 0) size = Math.round(context.getResources().getDisplayMetrics().density * 48);
            page.addView(button, new RelativeLayout.LayoutParams(size, size));
            page.addOnLayoutChangeListener(this);
            page.addOnAttachStateChangeListener(this);
            if (page.isAttachedToWindow()) onViewAttachedToWindow(page);
            place();
        }

        private void toggle(Context context) {
            boolean on = !SlowedPlayback.isEnabled();
            BoolSettings.SLOWED_PLAYBACK.set(on);
            SlowedPlayback.onSettingChanged();
            update();
            int percent = Math.round(SlowedPlayback.speed() * 100);
            Toast.makeText(context.getApplicationContext(), on
                    ? text("Slowed: " + percent + " %", "Slowed: " + percent + " %")
                    : text("Slowed выключен", "Slowed off"), Toast.LENGTH_SHORT).show();
        }

        /** The colour tells whether the tracks play slowed. */
        private void update() {
            boolean on = SlowedPlayback.isEnabled();
            if (shownOn != null && shownOn == on) return;
            shownOn = on;
            int[] colours = ArsoundTheme.darkColors(page.getContext());
            int accent = colours == null ? FALLBACK_ACCENT : colours[1];
            button.setImageTintList(on ? ColorStateList.valueOf(accent) : offTint);
            button.setSelected(on);
            button.setContentDescription(on ? text("Выключить slowed", "Switch slowed off")
                    : text("Включить slowed", "Switch slowed on"));
        }

        /** Left of the close button, at its height. Moves the picture only: no layout pass starts from here. */
        private void place() {
            if (close.getWidth() == 0) return;
            rect.set(0, 0, close.getWidth(), close.getHeight());
            try {
                page.offsetDescendantRectToMyCoords(close, rect);
            } catch (IllegalArgumentException ex) {
                return;
            }
            // The page is laid out in steps: a close button that is not in its place yet says nothing.
            if (rect.left <= 0 || page.getWidth() <= 0 || rect.right > page.getWidth()) return;
            ViewGroup.LayoutParams params = button.getLayoutParams();
            int size = Math.max(close.getWidth(), close.getHeight());
            if (params.width != size || params.height != size) {
                params.width = size;
                params.height = size;
                button.setLayoutParams(params);
            }
            float gap = page.getResources().getDisplayMetrics().density * GAP_DP;
            button.setTranslationX(rect.left - gap - size);
            button.setTranslationY(rect.top + (close.getHeight() - size) / 2f);
            keepTitleClear(Math.round(rect.left - gap - size));
        }

        /**
         * The title and the artist end before the new button, as they end before the buttons of the column: a long
         * title is cut short there, not run under the button.
         */
        private void keepTitleClear(int buttonLeft) {
            int headerId = id("track_page_header");
            View header = headerId == 0 ? null : page.findViewById(headerId);
            if (!(header instanceof ViewGroup)) return;
            int room = buttonLeft - Math.round(page.getResources().getDisplayMetrics().density * GAP_DP) - header.getLeft();
            limitTexts((ViewGroup) header, Math.max(room, 0), 0);
        }

        private void limitTexts(ViewGroup group, int room, int depth) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView) {
                    // The page's own limit is kept in the tag, and the new one is the smaller of the two.
                    TextView text = (TextView) child;
                    Object own = text.getTag(R_TAG);
                    if (own == null) {
                        own = text.getMaxWidth();
                        text.setTag(R_TAG, own);
                    }
                    int wanted = Math.min((Integer) own, room);
                    if (text.getMaxWidth() != wanted) text.setMaxWidth(wanted);
                } else if (child instanceof ViewGroup && depth < 2) {
                    limitTexts((ViewGroup) child, room, depth + 1);
                }
            }
        }

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop,
                                   int oldRight, int oldBottom) {
            place();
        }

        /** Before each frame: as visible as the close button, which the player fades with its top bar. */
        @Override
        public boolean onPreDraw() {
            try {
                View bar = (View) close.getParent();
                boolean visible = close.getVisibility() == View.VISIBLE && bar.getVisibility() == View.VISIBLE;
                float alpha = visible ? close.getAlpha() * bar.getAlpha() : 0f;
                int visibility = alpha > 0.01f ? View.VISIBLE : View.INVISIBLE;
                if (button.getVisibility() != visibility) button.setVisibility(visibility);
                if (visible && button.getAlpha() != alpha) button.setAlpha(alpha);
                if (visibility == View.VISIBLE) update();
            } catch (Throwable ex) {
                Logger.printException(() -> "Slowed button: could not follow the top bar", ex);
            }
            return true;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            view.getViewTreeObserver().addOnPreDrawListener(this);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            view.getViewTreeObserver().removeOnPreDrawListener(this);
        }
    }
}
