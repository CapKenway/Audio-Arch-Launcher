package com.audioarch.launcher.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

public class FpsGraphView extends View {

    private static final int MAX_SAMPLES = 40;
    private final int[] samples = new int[MAX_SAMPLES];
    private int sampleCount = 0;
    private int maxCeiling = 60;

    private Paint linePaint;
    private Paint fillPaint;
    private Paint gridPaint;
    private Paint targetLinePaint;
    private Paint textPaint;

    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    private float strokeWidthPx;
    private float targetTextSizePx;

    public FpsGraphView(Context context) {
        super(context);
        init();
    }

    public FpsGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FpsGraphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        strokeWidthPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 1.5f, getResources().getDisplayMetrics());
        targetTextSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 7.5f, getResources().getDisplayMetrics());

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(0xFF00E676); // Neon Green
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(strokeWidthPx);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(0x1AFFFFFF); // Faint grid line
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1.0f);

        targetLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        targetLinePaint.setColor(0x3300E676); // Subtle 60 FPS target line
        targetLinePaint.setStyle(Paint.Style.STROKE);
        targetLinePaint.setStrokeWidth(1.0f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0x6600E676);
        textPaint.setTextSize(targetTextSizePx);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h > 0) {
            // Neon Green semi-transparent gradient
            fillPaint.setShader(new LinearGradient(
                    0, 0, 0, h,
                    0x4000E676,
                    0x0400E676,
                    Shader.TileMode.CLAMP
            ));
        }
    }

    public void addFps(int fps) {
        int clamped = Math.max(0, fps);
        if (sampleCount < MAX_SAMPLES) {
            samples[sampleCount++] = clamped;
        } else {
            System.arraycopy(samples, 1, samples, 0, MAX_SAMPLES - 1);
            samples[MAX_SAMPLES - 1] = clamped;
        }

        // Recalculate auto-scaling ceiling
        int peak = 60;
        for (int i = 0; i < sampleCount; i++) {
            if (samples[i] > peak) {
                peak = samples[i];
            }
        }
        // Round ceiling to friendly steps: 60, 90, 120, 144, 165, 240
        if (peak <= 60) maxCeiling = 60;
        else if (peak <= 90) maxCeiling = 90;
        else if (peak <= 120) maxCeiling = 120;
        else if (peak <= 144) maxCeiling = 144;
        else if (peak <= 165) maxCeiling = 165;
        else if (peak <= 240) maxCeiling = 240;
        else maxCeiling = ((peak / 50) + 1) * 50;

        postInvalidate();
    }

    public void clear() {
        sampleCount = 0;
        maxCeiling = 60;
        postInvalidate();
    }

    public int getLatestFps() {
        if (sampleCount == 0) return 0;
        return samples[sampleCount - 1];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Draw bottom baseline and middle grid
        float midY = h * 0.5f;
        canvas.drawLine(0, midY, w, midY, gridPaint);

        // Draw 60 FPS reference guide if scale is larger than 60
        if (maxCeiling > 60) {
            float guide60Y = h - (60f / maxCeiling) * (h - strokeWidthPx * 2) - strokeWidthPx;
            canvas.drawLine(0, guide60Y, w, guide60Y, targetLinePaint);
            canvas.drawText("60", 3, guide60Y - 2, textPaint);
        }

        if (sampleCount <= 1) {
            // No history or single point
            return;
        }

        // Check if there are any non-zero samples
        boolean hasData = false;
        for (int i = 0; i < sampleCount; i++) {
            if (samples[i] > 0) {
                hasData = true;
                break;
            }
        }
        if (!hasData) {
            // Do not draw flat baseline when there is no valid FPS data
            return;
        }

        linePath.rewind();
        fillPath.rewind();

        final float stepX = (float) w / (MAX_SAMPLES - 1);
        final float usableHeight = h - strokeWidthPx * 2;
        final float startOffset = (MAX_SAMPLES - sampleCount) * stepX;

        float firstX = startOffset;
        float firstY = h - strokeWidthPx - (samples[0] / (float) maxCeiling) * usableHeight;
        firstY = Math.max(strokeWidthPx, Math.min(h - strokeWidthPx, firstY));

        linePath.moveTo(firstX, firstY);
        fillPath.moveTo(firstX, h);
        fillPath.lineTo(firstX, firstY);

        float lastX = firstX;
        float lastY = firstY;

        for (int i = 1; i < sampleCount; i++) {
            float x = startOffset + i * stepX;
            float y = h - strokeWidthPx - (samples[i] / (float) maxCeiling) * usableHeight;
            y = Math.max(strokeWidthPx, Math.min(h - strokeWidthPx, y));

            // Smooth cubic bezier or straight segment
            float prevX = lastX;
            float prevY = lastY;
            float midX = (prevX + x) / 2f;

            linePath.cubicTo(midX, prevY, midX, y, x, y);
            fillPath.cubicTo(midX, prevY, midX, y, x, y);

            lastX = x;
            lastY = y;
        }

        fillPath.lineTo(lastX, h);
        fillPath.close();

        // Draw gradient fill under graph
        canvas.drawPath(fillPath, fillPaint);
        // Draw graph stroke line
        canvas.drawPath(linePath, linePaint);

        // Draw tiny current FPS dot at the head of the graph
        canvas.drawCircle(lastX, lastY, strokeWidthPx * 1.5f, linePaint);
    }
}

