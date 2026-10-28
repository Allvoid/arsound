package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;

/** A tick box in the colours of the theme. */
final class TickBox extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tick = new Path();
    private final int on, off;
    private boolean checked;

    TickBox(Context context, int on, int off) {
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
        float stroke = Dimens.dpExact(getContext(), 2);
        float radius = Dimens.dpExact(getContext(), 5);
        RectF box = new RectF(stroke / 2, stroke / 2, getWidth() - stroke / 2, getHeight() - stroke / 2);
        if (checked) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on);
            canvas.drawRoundRect(box, radius, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Colors.contrast(on));
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
