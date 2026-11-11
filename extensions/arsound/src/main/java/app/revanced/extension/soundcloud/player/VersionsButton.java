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

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.recommendations.DislikeAction;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The round button of the full-screen player that opens the versions of the track ({@link VersionsPopup}: slowed, sped
 * up, reverb, bass boost). It is lit while any version is on. It stands in the top
 * row, left of the button that closes the player (between the title and that button), looks like the buttons of the
 * column on the right and follows the top bar: it is as visible as the close button is.
 * <p>
 * It is made in code and put on the page of the track, not into the layout of the player: the column of buttons is a
 * layout that is as wide as its widest button and keeps the close button in the middle of it, so a button added to the
 * left would push the close button away from the edge.
 */
public final class VersionsButton {
    private static final int FALLBACK_ACCENT = 0xFFFF5500;
    private static final int GAP_DP = 8;

    /** The buttons of the top row, from the close button to the left: what each shows and does. */
    private enum Kind {
        VERSIONS("arsound_slowed") {
            @Override
            boolean available() {
                return true;
            }

            @Override
            boolean lit() {
                return TrackVersions.anyOn();
            }

            @Override
            String description() {
                return lit() ? text("Версии трека: " + TrackVersions.summary(), "Versions of the track: " + TrackVersions.summary())
                        : text("Версии трека", "Versions of the track");
            }

            @Override
            void onTap(Context context, Runnable update) {
                VersionsPopup.show(context, update);
            }
        },
        DISLIKE("arsound_dislike") {
            @Override
            boolean available() {
                return DislikeAction.isAvailable();
            }

            @Override
            boolean lit() {
                return false;
            }

            @Override
            String description() {
                return text("Не нравится: не рекомендовать и включить следующий", "Not for me: do not recommend and play the next one");
            }

            @Override
            void onTap(Context context, Runnable update) {
                DislikeAction.run(context);
            }
        };

        final String icon;

        Kind(String icon) {
            this.icon = icon;
        }

        abstract boolean available();

        /** Whether the button is coloured with the accent. */
        abstract boolean lit();

        abstract String description();

        abstract void onTap(Context context, Runnable update);
    }

    private VersionsButton() {
    }

    /** Called when a player page is filled with a track: the page gets its button once. */
    public static void attach(View page) {
        try {
            if (!(page instanceof RelativeLayout) || page.getTag(R_TAG) != null) return;
            int closeId = id("player_close_indicator");
            View close = closeId == 0 ? null : page.findViewById(closeId);
            if (!(close instanceof ImageButton) || !(close.getParent() instanceof View)) return;
            int slot = 0;
            for (Kind kind : Kind.values()) {
                int icon = Utils.getResourceIdentifier(ResourceType.DRAWABLE, kind.icon);
                if (!kind.available() || icon == 0) continue;
                new Follower((RelativeLayout) page, (ImageButton) close, icon, kind, slot++);
            }
            // The page is marked once, even with nothing added.
            page.setTag(R_TAG, Boolean.TRUE);
        } catch (Throwable ex) {
            Logger.printException(() -> "Slowed button: could not add it to the player page", ex);
        }
    }

    private static final int R_TAG = 0x41534c57;

    /** The place of the leftmost button that is shown. */
    private static int lastSlot() {
        int last = -1;
        for (Kind kind : Kind.values()) {
            if (kind.available() && Utils.getResourceIdentifier(ResourceType.DRAWABLE, kind.icon) != 0) last++;
        }
        return last;
    }

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
        private final Kind kind;
        /** 0 stands next to the close button, 1 next to that one, and so on to the left. */
        private final int slot;
        /** Only the leftmost button makes the title end before itself: the ones right of it are further from the title. */
        private final boolean leftmost;

        Follower(RelativeLayout page, ImageButton close, int icon, Kind kind, int slot) {
            this.page = page;
            this.close = close;
            this.kind = kind;
            this.slot = slot;
            leftmost = slot == lastSlot();
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
            kind.onTap(page.getContext(), this::update);
        }

        /** The colour tells whether the button is on. */
        private void update() {
            boolean on = kind.lit();
            // Before the early return: the description names the chosen version, which changes while the button
            // stays lit, and a screen reader went on reading the old one.
            button.setContentDescription(kind.description());
            if (shownOn != null && shownOn == on) return;
            shownOn = on;
            int[] colours = ArsoundTheme.darkColors(page.getContext());
            int accent = colours == null ? FALLBACK_ACCENT : colours[1];
            button.setImageTintList(on ? ColorStateList.valueOf(accent) : offTint);
            button.setSelected(on);
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
            button.setTranslationX(rect.left - (gap + size) * (slot + 1));
            button.setTranslationY(rect.top + (close.getHeight() - size) / 2f);
            if (leftmost) keepTitleClear(Math.round(rect.left - (gap + size) * (slot + 1)));
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
