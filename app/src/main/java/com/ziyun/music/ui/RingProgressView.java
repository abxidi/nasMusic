package com.ziyun.music.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public class RingProgressView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float progress = 0.72f;

    public RingProgressView(Context context) {
        super(context);
    }

    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float stroke = Math.max(8f, getWidth() * 0.11f);
        float inset = stroke / 2f + 2f;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(stroke);
        paint.setColor(0xFFE5E7EB);
        canvas.drawArc(rect, -90, 360, false, paint);
        paint.setColor(0xFF7C3AED);
        canvas.drawArc(rect, -90, progress * 360f, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTextSize(getWidth() * 0.2f);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float y = getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(Math.round(progress * 100f) + "%", getWidth() / 2f, y, paint);
    }
}
