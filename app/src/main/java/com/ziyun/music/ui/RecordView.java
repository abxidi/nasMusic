package com.ziyun.music.ui;

import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class RecordView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path coverPath = new Path();
    private final RectF rect = new RectF();
    private ValueAnimator animator;
    private float rotation;
    private int colorStart = 0xFF581C87;
    private int colorEnd = 0xFFEC4899;
    private String mark = "V";
    private boolean playing = true;

    public RecordView(Context context) {
        super(context);
        init();
    }

    public RecordView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void configure(int colorStart, int colorEnd, String mark) {
        this.colorStart = colorStart;
        this.colorEnd = colorEnd;
        this.mark = mark == null || mark.isEmpty() ? "V" : mark;
        invalidate();
    }

    public void bind(com.ziyun.music.model.Song song, boolean playing) {
        if (song != null) {
            configure(song.colorStart, song.colorEnd, song.coverLetter);
        }
        setPlaying(playing);
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        if (playing) {
            startSpin();
        } else {
            stopSpin();
        }
        invalidate();
    }

    private void init() {
        textPaint.setColor(0xE8FFFFFF);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        startSpin();
    }

    private void startSpin() {
        if (animator != null && animator.isStarted()) {
            return;
        }
        animator = ValueAnimator.ofFloat(rotation, rotation + 360f);
        animator.setDuration(18000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            rotation = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopSpin() {
        if (animator != null) {
            animator.cancel();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpin();
        super.onDetachedFromWindow();
    }

    @SuppressLint("DrawAllocation")
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h * 0.63f;
        float radius = Math.min(w * 0.44f, h * 0.34f);

        drawRecord(canvas, cx, cy, radius);
        if (!playing) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x44000000);
            canvas.drawCircle(cx, cy, radius, paint);
        }
        drawTonearm(canvas, cx, cy, radius);
    }

    private void drawRecord(Canvas canvas, float cx, float cy, float radius) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x352F2942);
        canvas.drawCircle(cx, cy, radius + dp(16), paint);
        paint.setColor(0x1EFFFFFF);
        canvas.drawCircle(cx, cy, radius + dp(9), paint);

        canvas.save();
        canvas.rotate(rotation, cx, cy);

        paint.setShader(new RadialGradient(cx, cy, radius, 0xFF1A1B20, 0xFF050508, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        for (int i = 0; i < 22; i++) {
            paint.setColor(i % 3 == 0 ? 0x18FFFFFF : 0x0CFFFFFF);
            canvas.drawCircle(cx, cy, radius - dp(12) - i * dp(5.4f), paint);
        }

        float coverRadius = radius * 0.56f;
        coverPath.reset();
        coverPath.addCircle(cx, cy, coverRadius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(coverPath);

        rect.set(cx - coverRadius, cy - coverRadius, cx + coverRadius, cy + coverRadius);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom, colorStart, colorEnd, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, paint);
        paint.setShader(null);

        paint.setColor(0x3DFFFFFF);
        canvas.drawRect(cx - coverRadius * 0.58f, cy - coverRadius * 0.82f, cx - coverRadius * 0.46f, cy + coverRadius * 0.46f, paint);
        canvas.drawRect(cx - coverRadius * 0.22f, cy - coverRadius * 0.62f, cx - coverRadius * 0.08f, cy + coverRadius * 0.70f, paint);
        canvas.drawRect(cx + coverRadius * 0.26f, cy - coverRadius * 0.76f, cx + coverRadius * 0.42f, cy + coverRadius * 0.30f, paint);

        paint.setColor(0x36FFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        canvas.drawLine(cx - coverRadius * 0.74f, cy - coverRadius * 0.26f, cx + coverRadius * 0.52f, cy - coverRadius * 0.68f, paint);
        canvas.drawLine(cx - coverRadius * 0.52f, cy - coverRadius * 0.44f, cx + coverRadius * 0.76f, cy + coverRadius * 0.40f, paint);

        paint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(coverRadius * 0.32f);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        canvas.drawText(mark, cx + coverRadius * 0.42f, cy + coverRadius * 0.52f - (metrics.ascent + metrics.descent) / 2f, textPaint);
        canvas.restore();

        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(5));
        paint.setColor(0xFF08090D);
        canvas.drawCircle(cx, cy, coverRadius, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF050508);
        canvas.drawCircle(cx, cy, dp(8), paint);
        paint.setColor(0xFFEFEAF6);
        canvas.drawCircle(cx, cy, dp(3), paint);

        canvas.restore();
    }

    private void drawTonearm(Canvas canvas, float cx, float cy, float radius) {
        float pivotX = cx;
        float pivotY = getHeight() * 0.12f;
        float needleX = cx + radius * 0.44f;
        float needleY = cy - radius * 0.43f;

        path.reset();
        path.moveTo(pivotX, pivotY + dp(12));
        path.cubicTo(pivotX + dp(12), pivotY + dp(60), needleX - dp(28), needleY - dp(8), needleX, needleY);

        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(14));
        paint.setColor(0x22000000);
        canvas.drawPath(path, paint);

        paint.setStrokeWidth(dp(10));
        paint.setColor(0xFFF8F5FA);
        canvas.drawPath(path, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFF8F5FA);
        canvas.drawCircle(pivotX, pivotY, dp(17), paint);
        paint.setColor(0x33201A2D);
        canvas.drawCircle(pivotX, pivotY, dp(8), paint);

        canvas.save();
        canvas.rotate(38, needleX, needleY);
        rect.set(needleX - dp(16), needleY - dp(8), needleX + dp(20), needleY + dp(16));
        paint.setColor(0xFFF8F5FA);
        canvas.drawRoundRect(rect, dp(4), dp(4), paint);
        paint.setColor(0x55201A2D);
        paint.setStrokeWidth(dp(2));
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(needleX - dp(7), needleY - dp(5), needleX - dp(7), needleY + dp(12), paint);
        canvas.drawLine(needleX + dp(3), needleY - dp(5), needleX + dp(3), needleY + dp(12), paint);
        canvas.restore();

        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
