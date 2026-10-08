package com.example.cameraapp.yolo;

import android.graphics.RectF;

public class BoxData {
    private final RectF rect;
    private final int color;
    private final String label;

    public BoxData(RectF rect, int color, String label) {
        this.rect = rect;
        this.color = color;
        this.label = label;
    }

    public RectF getRect() { return rect; }
    public int getColor() { return color; }
    public String getLabel() { return label; }
}
