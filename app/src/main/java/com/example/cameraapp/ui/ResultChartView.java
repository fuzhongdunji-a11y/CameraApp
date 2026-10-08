package com.example.cameraapp.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 日付ごとの件数を棒グラフで描画するビュー（縦軸: 件数、横軸: 日付）。
 * 棒をタップするとその日付の件数を表示する。
 */
public class ResultChartView extends View {

    private static final int TEXT_PRIMARY = Color.parseColor("#0B0B0B");
    private static final int TEXT_SECONDARY = Color.parseColor("#52514E");
    private static final int GRID = Color.parseColor("#E4E3DF");
    private static final int AXIS = Color.parseColor("#B5B4AE");

    private final List<String> labels = new ArrayList<>();
    private final List<Integer> values = new ArrayList<>();
    private int barColor = Color.parseColor("#2A78D6");
    private String emptyText = "データがありません";
    private int selected = -1;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path barPath = new Path();
    private final RectF rect = new RectF();
    private final float dp;

    // 描画領域（onDraw で計算し、タップ判定に使う）
    private float plotLeft, plotRight, slotWidth;

    public ResultChartView(Context context) {
        this(context, null);
    }

    public ResultChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        gridPaint.setColor(GRID);
        gridPaint.setStrokeWidth(1 * dp);
        axisPaint.setColor(AXIS);
        axisPaint.setStrokeWidth(1 * dp);
        textPaint.setTextSize(11 * dp * getResources().getConfiguration().fontScale);
        tipPaint.setColor(Color.WHITE);
    }

    /** 日付ラベルと件数を設定する（同じ長さ） */
    public void setData(List<String> labels, List<Integer> values, int color) {
        this.labels.clear();
        this.labels.addAll(labels);
        this.values.clear();
        this.values.addAll(values);
        this.barColor = color;
        this.selected = -1;
        invalidate();
    }

    public void setEmptyText(String text) {
        this.emptyText = text;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();

        if (values.isEmpty()) {
            textPaint.setColor(TEXT_SECONDARY);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(emptyText, w / 2, h / 2, textPaint);
            return;
        }

        int max = 0;
        for (int v : values) max = Math.max(max, v);
        int step = niceStep(max);
        int top = Math.max(step, ((max + step - 1) / step) * step);

        // 余白（左: 縦軸の目盛り、下: 日付、上: タップ時の件数表示）
        String maxLabel = String.valueOf(top);
        float textH = textPaint.getTextSize();
        plotLeft = getPaddingLeft() + textPaint.measureText(maxLabel) + 8 * dp;
        plotRight = w - getPaddingRight() - 4 * dp;
        float plotTop = getPaddingTop() + textH + 12 * dp;
        float plotBottom = h - getPaddingBottom() - textH - 8 * dp;
        float plotH = plotBottom - plotTop;

        // 横の目盛り線と件数
        textPaint.setColor(TEXT_SECONDARY);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        for (int v = 0; v <= top; v += step) {
            float y = plotBottom - plotH * v / top;
            if (v > 0) canvas.drawLine(plotLeft, y, plotRight, y, gridPaint);
            canvas.drawText(String.valueOf(v), plotLeft - 6 * dp, y + textH / 3, textPaint);
        }

        // 棒（隣の棒との間に 2dp 以上の隙間、上端のみ角丸）
        int n = values.size();
        slotWidth = (plotRight - plotLeft) / n;
        float gap = Math.max(2 * dp, slotWidth * 0.3f);
        float barW = Math.min(Math.max(slotWidth - gap, 1), 40 * dp);
        float radius = Math.min(4 * dp, barW / 2);
        barPaint.setColor(barColor);
        for (int i = 0; i < n; i++) {
            int v = values.get(i);
            if (v <= 0) continue;
            float cx = plotLeft + slotWidth * (i + 0.5f);
            float barTop = plotBottom - plotH * v / top;
            barPaint.setAlpha(selected < 0 || selected == i ? 255 : 110);
            drawTopRoundedBar(canvas, cx - barW / 2, barTop, cx + barW / 2, plotBottom, radius);
        }
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axisPaint);

        // 日付ラベル（重ならないように間引く。最後の日付は常に表示）
        textPaint.setColor(TEXT_SECONDARY);
        textPaint.setTextAlign(Paint.Align.CENTER);
        float labelW = textPaint.measureText("00/00") + 8 * dp;
        int every = Math.max(1, (int) Math.ceil(labelW / slotWidth));
        float labelY = plotBottom + textH + 4 * dp;
        for (int i = n - 1; i >= 0; i -= every) {
            canvas.drawText(labels.get(i), plotLeft + slotWidth * (i + 0.5f), labelY, textPaint);
        }

        // タップした棒の件数
        if (selected >= 0 && selected < n) {
            String tip = labels.get(selected) + "  " + values.get(selected) + "件";
            float cx = plotLeft + slotWidth * (selected + 0.5f);
            float tw = textPaint.measureText(tip) + 12 * dp;
            float left = Math.max(plotLeft, Math.min(cx - tw / 2, plotRight - tw));
            rect.set(left, getPaddingTop(), left + tw, getPaddingTop() + textH + 8 * dp);
            tipPaint.setColor(TEXT_PRIMARY);
            canvas.drawRoundRect(rect, 4 * dp, 4 * dp, tipPaint);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(tip, rect.centerX(), rect.bottom - 4 * dp - textH * 0.15f, textPaint);
        }
    }

    private void drawTopRoundedBar(Canvas canvas, float l, float t, float r, float b, float radius) {
        float rad = Math.min(radius, b - t);
        barPath.reset();
        barPath.moveTo(l, b);
        barPath.lineTo(l, t + rad);
        barPath.quadTo(l, t, l + rad, t);
        barPath.lineTo(r - rad, t);
        barPath.quadTo(r, t, r, t + rad);
        barPath.lineTo(r, b);
        barPath.close();
        canvas.drawPath(barPath, barPaint);
    }

    /** 目盛りが5本程度になるように 1, 2, 5, 10, 20, 50... から間隔を選ぶ */
    private static int niceStep(int max) {
        if (max <= 5) return 1;
        int base = 1;
        while (true) {
            for (int m : new int[]{1, 2, 5}) {
                int s = base * m;
                if (max / s <= 5) return s;
            }
            base *= 10;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (values.isEmpty() || slotWidth <= 0) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float x = event.getX();
            int i = (int) ((x - plotLeft) / slotWidth);
            if (x < plotLeft || x > plotRight || i < 0 || i >= values.size() || i == selected) {
                selected = -1;
            } else {
                selected = i;
            }
            invalidate();
            performClick();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
