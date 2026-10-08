package com.example.cameraapp.yolo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BoundingBoxView extends View {

    private List<BoxData> boxes = Collections.emptyList();

    private final Paint boxPaint = new Paint();
    private final Paint textPaint = new Paint();

    public BoundingBoxView(Context context) {
        this(context, null);
    }

    public BoundingBoxView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BoundingBoxView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(8f);
        boxPaint.setAntiAlias(true);

        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(42f);
        textPaint.setAntiAlias(true);
    }

    public void setBoxes(List<BoxData> newBoxes) {
        this.boxes = newBoxes;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (boxes.isEmpty()) return;

        for (BoxData boxData : boxes) {
            boxPaint.setColor(boxData.getColor());
            textPaint.setColor(boxData.getColor());

            canvas.drawRect(boxData.getRect(), boxPaint);

            if (boxData.getLabel() != null && !boxData.getLabel().isEmpty()) {
                canvas.drawText(
                        boxData.getLabel(),
                        boxData.getRect().left,
                        Math.max(boxData.getRect().top - 10f, textPaint.getTextSize()),
                        textPaint
                );
            }
        }
    }

    public void setResults(List<YoloDetector.Detection> detections, int inputImageWidth, int inputImageHeight) {
        if (getWidth() == 0 || getHeight() == 0) return;

        float scaleX = (float) getWidth() / inputImageWidth;
        float scaleY = (float) getHeight() / inputImageHeight;

        List<BoxData> newBoxes = new ArrayList<>();
        for (YoloDetector.Detection detection : detections) {
            RectF scaledRect = new RectF(
                    (detection.x - detection.w / 2) * scaleX,
                    (detection.y - detection.h / 2) * scaleY,
                    (detection.x + detection.w / 2) * scaleX,
                    (detection.y + detection.h / 2) * scaleY
            );

            String label = detection.label;
            int color = getColorForLabel(label);

            newBoxes.add(new BoxData(
                    scaledRect,
                    color,
                    label + " " + (int) (detection.score * 100) + "%"
            ));
        }

        setBoxes(newBoxes);
    }

    private int getColorForLabel(String label) {
        int hash = label.hashCode();
        int r = (hash & 0xFF0000) >> 16;
        int g = (hash & 0x00FF00) >> 8;
        int b = (hash & 0x0000FF);
        return Color.rgb(r, g, b);
    }
}
