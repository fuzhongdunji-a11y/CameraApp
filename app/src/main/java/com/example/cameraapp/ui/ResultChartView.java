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
 * 日付ごとの件数を折れ線グラフで描画するビュー（縦軸: 件数、横軸: 日付）。
 * グラフをタップ（またはなぞる）と、いちばん近い日付の件数を表示する。
 */
public class ResultChartView extends View {

    private static final int TEXT_PRIMARY = Color.parseColor("#0B0B0B");
    private static final int TEXT_SECONDARY = Color.parseColor("#52514E");
    private static final int GRID = Color.parseColor("#E4E3DF");
    private static final int AXIS = Color.parseColor("#B5B4AE");
    // 画面の背景色（点の縁取りに使い、線と点を見分けやすくする）
    private static final int SURFACE = Color.parseColor("#FCFCFB");

    private final List<String> labels = new ArrayList<>();
    private final List<Integer> values = new ArrayList<>();
    private int lineColor = Color.parseColor("#2A78D6");
    private String emptyText = "データがありません";
    private int selected = -1;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
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
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2 * dp);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setColor(SURFACE);
        gridPaint.setColor(GRID);
        gridPaint.setStrokeWidth(1 * dp);
        axisPaint.setColor(AXIS);
        axisPaint.setStrokeWidth(1 * dp);
        crosshairPaint.setColor(AXIS);
        crosshairPaint.setStrokeWidth(1 * dp);
        textPaint.setTextSize(11 * dp * getResources().getConfiguration().fontScale);
        tipPaint.setColor(TEXT_PRIMARY);
    }

    /** 日付ラベルと件数を設定する（同じ長さ） */
    public void setData(List<String> labels, List<Integer> values, int color) {
        this.labels.clear();
        this.labels.addAll(labels);
        this.values.clear();
        this.values.addAll(values);
        this.lineColor = color;
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
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axisPaint);

        // 各日付の点の位置（日付ごとに等間隔の枠の中央）
        int n = values.size();
        slotWidth = (plotRight - plotLeft) / n;
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = plotLeft + slotWidth * (i + 0.5f);
            ys[i] = plotBottom - plotH * values.get(i) / top;
        }

        // 選択した日付の縦線
        if (selected >= 0 && selected < n) {
            canvas.drawLine(xs[selected], plotTop, xs[selected], plotBottom, crosshairPaint);
        }

        // 折れ線（件数 0 の日も 0 として線でつなぐ）
        linePaint.setColor(lineColor);
        if (n > 1) {
            linePath.reset();
            linePath.moveTo(xs[0], ys[0]);
            for (int i = 1; i < n; i++) linePath.lineTo(xs[i], ys[i]);
            canvas.drawPath(linePath, linePaint);
        }

        // 点（日付が多く間隔が狭い場合は、選択した点と1日だけの場合のみ描く）
        dotPaint.setColor(lineColor);
        float radius = 4 * dp;
        float ring = 2 * dp;
        boolean showDots = slotWidth >= 12 * dp || n == 1;
        for (int i = 0; i < n; i++) {
            if (!showDots && i != selected) continue;
            float r = i == selected ? radius + 1.5f * dp : radius;
            canvas.drawCircle(xs[i], ys[i], r + ring, ringPaint);
            canvas.drawCircle(xs[i], ys[i], r, dotPaint);
        }

        // 日付ラベル（重ならないように間引く。最後の日付は常に表示）
        textPaint.setColor(TEXT_SECONDARY);
        textPaint.setTextAlign(Paint.Align.CENTER);
        float labelW = textPaint.measureText("00/00") + 8 * dp;
        int every = Math.max(1, (int) Math.ceil(labelW / slotWidth));
        float labelY = plotBottom + textH + 4 * dp;
        for (int i = n - 1; i >= 0; i -= every) {
            canvas.drawText(labels.get(i), xs[i], labelY, textPaint);
        }

        // 選択した日付の件数
        if (selected >= 0 && selected < n) {
            String tip = labels.get(selected) + "  " + values.get(selected) + "件";
            float tw = textPaint.measureText(tip) + 12 * dp;
            float left = Math.max(plotLeft, Math.min(xs[selected] - tw / 2, plotRight - tw));
            rect.set(left, getPaddingTop(), left + tw, getPaddingTop() + textH + 8 * dp);
            canvas.drawRoundRect(rect, 4 * dp, 4 * dp, tipPaint);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(tip, rect.centerX(), rect.bottom - 4 * dp - textH * 0.15f, textPaint);
        }
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

    /** x 座標にいちばん近い日付の番号（グラフの範囲外なら -1） */
    private int indexAt(float x) {
        if (x < plotLeft - slotWidth / 2 || x > plotRight + slotWidth / 2) return -1;
        int i = (int) ((x - plotLeft) / slotWidth);
        return Math.max(0, Math.min(values.size() - 1, i));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (values.isEmpty() || slotWidth <= 0) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                // なぞっている間は親のスクロールに取られないようにする
                getParent().requestDisallowInterceptTouchEvent(true);
                int i = indexAt(event.getX());
                if (i != selected) {
                    selected = i;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
