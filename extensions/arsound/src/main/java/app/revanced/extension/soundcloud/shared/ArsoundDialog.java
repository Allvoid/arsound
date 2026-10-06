package app.revanced.extension.soundcloud.shared;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * A dialog in the look of the chosen theme: a rounded card of the theme's dialog colour with a thin accent edge,
 * SoundCloud's text styles (the theme's fonts), the main button as an accent pill. It slides and fades in.
 * <p>
 * Built like {@link android.app.AlertDialog.Builder}, with the parts Arsound uses, so it replaces it one for one.
 */
public final class ArsoundDialog {
    private final Context context;
    private final int accent, primary, secondary, surface;
    private CharSequence title, message;
    private View view;
    private CharSequence[] items;
    private int checked = -1;
    private DialogInterface.OnClickListener itemListener;
    private CharSequence positive, negative, neutral;
    private DialogInterface.OnClickListener onPositive, onNegative, onNeutral;
    private boolean cancelable = true;
    private DialogInterface.OnDismissListener onDismiss;

    public ArsoundDialog(Context context) {
        this.context = context;
        accent = ArsoundTheme.palette(context, "special");
        primary = ArsoundTheme.palette(context, "primary");
        secondary = ArsoundTheme.palette(context, "secondary");
        surface = ArsoundTheme.palette(context, "dialog");
    }

    public ArsoundDialog setTitle(CharSequence title) {
        this.title = title;
        return this;
    }

    public ArsoundDialog setMessage(CharSequence message) {
        this.message = message;
        return this;
    }

    public ArsoundDialog setView(View view) {
        this.view = view;
        return this;
    }

    public ArsoundDialog setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
        this.items = items;
        this.itemListener = listener;
        return this;
    }

    /** The listener dismisses the dialog itself, as with AlertDialog. */
    public ArsoundDialog setSingleChoiceItems(CharSequence[] items, int checked, DialogInterface.OnClickListener listener) {
        this.items = items;
        this.checked = checked;
        this.itemListener = listener;
        return this;
    }

    public ArsoundDialog setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
        positive = text;
        onPositive = listener;
        return this;
    }

    public ArsoundDialog setPositiveButton(int text, DialogInterface.OnClickListener listener) {
        return setPositiveButton(context.getString(text), listener);
    }

    public ArsoundDialog setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
        negative = text;
        onNegative = listener;
        return this;
    }

    public ArsoundDialog setNegativeButton(int text, DialogInterface.OnClickListener listener) {
        return setNegativeButton(context.getString(text), listener);
    }

    public ArsoundDialog setCancelable(boolean cancelable) {
        this.cancelable = cancelable;
        return this;
    }

    public ArsoundDialog setOnDismissListener(DialogInterface.OnDismissListener listener) {
        onDismiss = listener;
        return this;
    }

    public ArsoundDialog setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
        neutral = text;
        onNeutral = listener;
        return this;
    }

    public Dialog show() {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(cancelable);
        if (onDismiss != null) dialog.setOnDismissListener(onDismiss);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(surface);
        shape.setCornerRadius(dp(26));
        shape.setStroke(Math.max(1, dp(1)), (accent & 0x00FFFFFF) | 0x40000000);
        card.setBackground(shape);
        card.setPadding(0, dp(22), 0, dp(14));

        if (title != null) {
            TextView titleView = text("H3.Primary", title, primary);
            titleView.setPadding(dp(24), 0, dp(24), dp(10));
            card.addView(titleView);
        }
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        if (message != null) {
            TextView messageView = text("Body.Secondary", message, secondary);
            messageView.setPadding(dp(24), 0, dp(24), dp(8));
            body.addView(messageView);
        }
        if (view != null) {
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            tintFields(view);
            body.addView(view);
        }
        if (items != null) {
            for (int i = 0; i < items.length; i++) body.addView(item(dialog, i));
        }
        // A long text scrolls, so the buttons always stay on screen.
        int maxHeight = Math.round(context.getResources().getDisplayMetrics().heightPixels * .62f);
        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(body);
        card.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (positive != null || negative != null || neutral != null) {
            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            buttons.setPadding(dp(14), dp(10), dp(16), 0);
            if (neutral != null) {
                buttons.addView(button(dialog, neutral, onNeutral, DialogInterface.BUTTON_NEUTRAL, false));
                buttons.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
            }
            if (negative != null) buttons.addView(button(dialog, negative, onNegative, DialogInterface.BUTTON_NEGATIVE, false));
            if (positive != null) buttons.addView(button(dialog, positive, onPositive, DialogInterface.BUTTON_POSITIVE, true));
            card.addView(buttons);
        }

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(40), dp(420));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(0.55f);
        }
        card.setAlpha(0f);
        card.setScaleX(.94f);
        card.setScaleY(.94f);
        card.setTranslationY(dp(12));
        dialog.show();
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        return dialog;
    }

    /** A list row; with a chosen item, a ring that fills for it. */
    private View item(Dialog dialog, int index) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(50));
        row.setPadding(dp(24), dp(6), dp(24), dp(6));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x33000000), null,
                new ColorDrawable(Color.WHITE)));
        if (checked >= 0) {
            View ring = new View(context);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            boolean on = index == checked;
            circle.setStroke(dp(2), on ? accent : (primary & 0x00FFFFFF) | 0x66000000);
            if (on) circle.setColor(accent);
            ring.setBackground(circle);
            if (on) {
                // A dot of the card colour in the middle of the filled ring.
                GradientDrawable inner = new GradientDrawable();
                inner.setShape(GradientDrawable.OVAL);
                inner.setColor(surface);
                android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{circle, inner});
                layers.setLayerInset(1, dp(6), dp(6), dp(6), dp(6));
                ring.setBackground(layers);
            }
            LinearLayout.LayoutParams ringParams = new LinearLayout.LayoutParams(dp(20), dp(20));
            ringParams.rightMargin = dp(16);
            row.addView(ring, ringParams);
        }
        TextView label = text("Body.Primary", items[index], index == checked ? accent : primary);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> {
            if (itemListener != null) itemListener.onClick(dialog, index);
            // AlertDialog closes after a plain list item, not after a single-choice one.
            if (checked < 0) dialog.dismiss();
        });
        return row;
    }

    private View button(Dialog dialog, CharSequence label, DialogInterface.OnClickListener listener, int which, boolean main) {
        TextView button = text("H4.Primary", label, main ? contrast(accent) : accent);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(40));
        button.setPadding(dp(18), dp(8), dp(18), dp(8));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(100));
        shape.setColor(main ? accent : Color.TRANSPARENT);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(main ? 0x33FFFFFF : (accent & 0x00FFFFFF) | 0x33000000),
                shape, null));
        button.setOnClickListener(v -> {
            if (listener != null) listener.onClick(dialog, which);
            dialog.dismiss();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(6);
        button.setLayoutParams(params);
        return button;
    }

    /** Text fields inside the given view get the theme's colours. */
    private void tintFields(View view) {
        if (view instanceof EditText) {
            EditText field = (EditText) view;
            field.setTextColor(primary);
            field.setHintTextColor((secondary & 0x00FFFFFF) | 0x99000000);
            field.setBackgroundTintList(ColorStateList.valueOf(accent));
            field.setHighlightColor((accent & 0x00FFFFFF) | 0x55000000);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.graphics.drawable.Drawable cursor = field.getTextCursorDrawable();
                if (cursor != null) cursor.setTint(accent);
            }
        } else if (view instanceof TextView) {
            ((TextView) view).setTextColor(primary);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintFields(group.getChildAt(i));
        }
    }

    private TextView text(String style, CharSequence value, int color) {
        TextView view = new TextView(context);
        int id = Utils.getResourceIdentifier(ResourceType.STYLE, style);
        if (id != 0) view.setTextAppearance(id);
        view.setTextColor(color);
        view.setText(value);
        return view;
    }

    private static int contrast(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return luminance > 0.6 ? 0xFF111111 : 0xFFFFFFFF;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }
}
