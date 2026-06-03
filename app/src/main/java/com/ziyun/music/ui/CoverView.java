package com.ziyun.music.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public class CoverView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private String mark = "V";
    private int colorStart = 0xFF581C87;
    private int colorEnd = 0xFFEC4899;
    private float cornerRadius;
    private boolean circle;

    public CoverView(Context context) {
        super(context);
        init();
    }

    public CoverView(Context context, int colorStart, int colorEnd, String mark, float cornerRadius) {
        super(context);
        init();
        configure(colorStart, colorEnd, mark);
        setCornerRadius(cornerRadius);
    }

    public CoverView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void configure(int colorStart, int colorEnd, String mark) {
        this.colorStart = colorStart;
        this.colorEnd = colorEnd;
        this.mark = mark == null || mark.isEmpty() ? "V" : mark;
        invalidate();
    }

    public void setCover(int colorStart, int colorEnd, String mark) {
        configure(colorStart, colorEnd, mark);
    }

    public void setCircle(boolean circle) {
        this.circle = circle;
        invalidate();
    }

    public void setCornerRadius(float cornerRadius) {
        this.cornerRadius = cornerRadius;
        invalidate();
    }

    private void init() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        cornerRadius = dp(6);
        textPaint.setColor(0xDFFFFFFF);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @SuppressLint("DrawAllocation")
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        rect.set(0, 0, width, height);
        paint.setShader(new LinearGradient(0, 0, width, height, colorStart, colorEnd, Shader.TileMode.CLAMP));
        if (circle) {
            canvas.drawOval(rect, paint);
        } else {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint);
        }

        paint.setShader(null);
        paint.setColor(0x26FFFFFF);
        Path path = new Path();
        path.moveTo(width * 0.08f, height * 0.12f);
        path.lineTo(width * 0.95f, height * 0.58f);
        path.lineTo(width * 0.95f, height * 0.92f);
        path.close();
        canvas.drawPath(path, paint);

        textPaint.setTextSize(Math.max(dp(18), height * 0.38f));
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baseline = height - dp(8) - metrics.descent;
        canvas.drawText(mark, width * 0.72f, baseline, textPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
